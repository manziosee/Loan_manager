(ns loanmanager.api.server
  (:require [ring.adapter.jetty :as jetty]
            [reitit.ring :as ring]
            [reitit.ring.coercion :as coercion]
            [reitit.coercion.malli :as malli-coercion]
            [reitit.ring.middleware.muuntaja :as muuntaja]
            [reitit.ring.middleware.parameters :as parameters]
            [reitit.swagger :as swagger]
            [reitit.swagger-ui :as swagger-ui]
            [muuntaja.core :as m]
            [loanmanager.api.schemas :as schemas]
            [loanmanager.security.middleware :as sec]
            [loanmanager.api.routes.auth :as auth-routes]
            [loanmanager.api.routes.customers :as customer-routes]
            [loanmanager.api.routes.products :as product-routes]
            [loanmanager.api.routes.loans :as loan-routes]
            [loanmanager.api.routes.repayment :as repayment-routes]
            [loanmanager.api.routes.credit :as credit-routes]
            [loanmanager.api.routes.delinquency :as delinquency-routes]
            [loanmanager.api.routes.collections :as collection-routes]
            [loanmanager.api.routes.audit :as audit-routes]))

(defn create-handler [config ds bus]
  (let [sec-config (:security config)
        sw-config  (:swagger config)]
    (ring/ring-handler
     (ring/router
      [["/swagger.json"
        {:get {:no-doc  true
               :swagger {:info {:title       (:title sw-config)
                                :description (:description sw-config)
                                :version     (:version sw-config)}
                          :securityDefinitions
                          {:BearerAuth {:type "apiKey"
                                        :in   "header"
                                        :name "Authorization"}}
                          :security [{:BearerAuth []}]}
               :handler (swagger/create-swagger-handler)}}]

       ;; Public routes
       ["/api/v1"
        (auth-routes/routes ds config)
        ["/loans/simulate"
         {:post {:summary    "Simulate repayment schedule — all methods (public)"
                 :tags       ["Repayment Engine" "Tools"]
                 :parameters {:body schemas/SimulateRequest}
                 :handler    (fn [{:keys [body-params]}]
                               (let [{:keys [principal annual-rate months]} body-params
                                     schedule (loanmanager.domain.finance/build-schedule
                                                {:method          :reducing-balance
                                                 :principal       principal
                                                 :annual-rate     (/ annual-rate 100)
                                                 :duration-months months})
                                     summary  (loanmanager.domain.finance/schedule-summary schedule principal)]
                                 {:status 200 :body {:summary summary :schedule schedule}}))}}]]

       ;; Protected routes
       ["/api/v1"
        {:middleware [[sec/wrap-authentication sec-config]
                      sec/wrap-require-auth
                      sec/wrap-tenant]}
        (customer-routes/routes ds)
        (product-routes/routes ds)
        (loan-routes/routes ds bus)
        (repayment-routes/routes ds bus)
        (credit-routes/routes ds)
        (delinquency-routes/routes ds bus)
        (collection-routes/routes ds)
        (audit-routes/routes ds)]]

      {:data {:coercion   malli-coercion/coercion
              :muuntaja   m/instance
              :middleware [parameters/parameters-middleware
                           muuntaja/format-negotiate-middleware
                           muuntaja/format-response-middleware
                           muuntaja/format-request-middleware
                           coercion/coerce-exceptions-middleware
                           coercion/coerce-request-middleware
                           coercion/coerce-response-middleware
                           sec/wrap-exception
                           sec/wrap-security-headers
                           sec/wrap-request-log]}})

     (ring/routes
      (swagger-ui/create-swagger-ui-handler
       {:path   "/swagger-ui"
        :url    "/swagger.json"
        :config {:validatorUrl nil}})
      (ring/create-default-handler
       {:not-found (constantly {:status 404 :body {:error "Not Found"}})})))))

(defn start-server! [handler port]
  (jetty/run-jetty handler {:port port :join? false}))

(defn stop-server! [server]
  (.stop server))
