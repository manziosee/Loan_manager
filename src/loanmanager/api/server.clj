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
            [loanmanager.domain.finance :as finance]
            [loanmanager.security.middleware :as sec]
            [loanmanager.api.routes.health :as health-routes]
            [loanmanager.api.routes.auth :as auth-routes]
            [loanmanager.api.routes.customers :as customer-routes]
            [loanmanager.api.routes.products :as product-routes]
            [loanmanager.api.routes.loans :as loan-routes]
            [loanmanager.api.routes.repayment :as repayment-routes]
            [loanmanager.api.routes.credit :as credit-routes]
            [loanmanager.api.routes.delinquency :as delinquency-routes]
            [loanmanager.api.routes.collections :as collection-routes]
            [loanmanager.api.routes.audit :as audit-routes]
            [loanmanager.api.routes.users :as user-routes]
            [loanmanager.api.routes.reports :as report-routes]))

(def swagger-tags
  [{:name "System"          :description "Health checks and system status"}
   {:name "Authentication"  :description "Login, logout, token refresh, password management"}
   {:name "Users"           :description "User management and role assignment"}
   {:name "Customers"       :description "Customer profiles, KYC, and financial data"}
   {:name "Loan Products"   :description "Loan product catalogue management"}
   {:name "Applications"    :description "Loan application lifecycle"}
   {:name "Workflow"        :description "Maker-checker approval workflow"}
   {:name "Loans"           :description "Active loan management"}
   {:name "Disbursement"    :description "Loan disbursement"}
   {:name "Payments"        :description "Payment recording and reversal"}
   {:name "Repayment Engine":description "Schedule simulation, prepayment, and settlement"}
   {:name "Credit Scoring"  :description "Rules-based credit assessment and DTI analysis"}
   {:name "Delinquency"     :description "Delinquency classification and NPL management"}
   {:name "Collections"     :description "Collections case management and promise-to-pay"}
   {:name "Portfolio"       :description "Portfolio-wide delinquency and NPL summary"}
   {:name "Reports"         :description "Financial and operational reporting"}
   {:name "Audit"           :description "Immutable audit trail"}
   {:name "Tools"           :description "Public simulation and calculation tools"}])

(defn create-handler [config ds bus]
  (let [sec-config (:security config)
        sw-config  (:swagger config)]
    (ring/ring-handler
     (ring/router
      [;; ── Swagger spec ──────────────────────────────────────────────────────
       ["/swagger.json"
        {:get {:no-doc  true
               :swagger {:info    {:title       (:title sw-config "LoanOS API")
                                   :description (:description sw-config "Bank-grade Loan Management Platform")
                                   :version     (:version sw-config "1.0.0")
                                   :contact     {:name  "LoanOS Support"
                                                 :email "support@loanmanager.local"}
                                   :license     {:name "Proprietary"}}
                          :tags    swagger-tags
                          :securityDefinitions
                          {:BearerAuth {:type        "apiKey"
                                        :in          "header"
                                        :name        "Authorization"
                                        :description "JWT token — prefix with 'Bearer '"}}
                          :security [{:BearerAuth []}]}
               :handler (swagger/create-swagger-handler)}}]

       ;; ── Public routes (no auth) ───────────────────────────────────────────
       ["/api/v1"
        (health-routes/routes ds)
        (auth-routes/routes ds config)
        ["/loans/simulate"
         {:post {:summary    "Simulate repayment schedule — all methods (public)"
                 :tags       ["Repayment Engine" "Tools"]
                 :parameters {:body schemas/SimulateRequest}
                 :handler    (fn [{:keys [body-params]}]
                               (let [{:keys [principal annual-rate months method frequency
                                             grace-period-months balloon-pct]} body-params
                                     freq     (keyword (or frequency "monthly"))
                                     meth     (keyword (or method "reducing_balance"))
                                     schedule (finance/build-schedule
                                                {:method              meth
                                                 :principal           principal
                                                 :annual-rate         (/ annual-rate 100)
                                                 :duration-months     months
                                                 :freq                freq
                                                 :grace-period-months (or grace-period-months 0)
                                                 :balloon-pct         balloon-pct})
                                     summary  (finance/schedule-summary schedule principal)]
                                 {:status 200
                                  :body   {:method   (name meth)
                                           :frequency (name freq)
                                           :summary  summary
                                           :schedule schedule}}))}}]]

       ;; ── Protected routes (JWT required) ───────────────────────────────────
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
        (audit-routes/routes ds)
        (user-routes/routes ds)
        (report-routes/routes ds)]]

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
        :config {:validatorUrl         nil
                 :displayRequestDuration true
                 :docExpansion         "list"
                 :filter               true
                 :tryItOutEnabled      true}})
      (ring/create-default-handler
       {:not-found          (constantly {:status 404 :body {:error "Not Found"}})
        :method-not-allowed (constantly {:status 405 :body {:error "Method Not Allowed"}})})))))

(defn start-server! [handler port]
  (jetty/run-jetty handler {:port  port
                             :join? false
                             :min-threads 4
                             :max-threads 50}))

(defn stop-server! [server]
  (.stop server))
