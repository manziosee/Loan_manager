(ns loanmanager.api.routes.reports
  (:require [loanmanager.api.schemas :as schemas]
            [loanmanager.db.loans :as loans-db]
            [loanmanager.security.rbac :as rbac]))

(defn- parse-date [s] (java.time.LocalDate/parse s))

(defn routes [ds]
  [["/reports"
    ["/portfolio"
     {:get {:summary    "Portfolio summary — PAR buckets, NPL ratio, totals"
            :tags       ["Reports"]
            :handler    (fn [{:keys [identity tenant-id]}]
                          (rbac/require-permission identity :portfolio/read)
                          (let [by-status (loans-db/portfolio-summary ds tenant-id)
                                par       (loans-db/par-buckets ds tenant-id)
                                total-outstanding (reduce + (map #(or (:outstanding %) 0) by-status))
                                npl-outstanding   (->> by-status
                                                       (filter #(= "npl" (:status %)))
                                                       (map #(or (:outstanding %) 0))
                                                       (reduce + 0))
                                npl-ratio (if (pos? total-outstanding)
                                            (double (/ npl-outstanding total-outstanding))
                                            0.0)]
                            {:status 200
                             :body   {:by-status        by-status
                                      :par-buckets      par
                                      :total-outstanding total-outstanding
                                      :npl-outstanding   npl-outstanding
                                      :npl-ratio         npl-ratio}}))}}]

    ["/disbursements"
     {:get {:summary    "Disbursement report by month"
            :tags       ["Reports"]
            :parameters {:query schemas/DateRangeQuery}
            :handler    (fn [{:keys [identity tenant-id query-params]}]
                          (rbac/require-permission identity :portfolio/read)
                          (let [{:keys [from to]} query-params]
                            {:status 200
                             :body   (loans-db/disbursements-by-period
                                       ds tenant-id
                                       (parse-date from)
                                       (parse-date to))}))}}]

    ["/income"
     {:get {:summary    "Interest income and principal collected by month"
            :tags       ["Reports"]
            :parameters {:query schemas/DateRangeQuery}
            :handler    (fn [{:keys [identity tenant-id query-params]}]
                          (rbac/require-permission identity :ledger/read)
                          (let [{:keys [from to]} query-params]
                            {:status 200
                             :body   (loans-db/income-by-period
                                       ds tenant-id
                                       (parse-date from)
                                       (parse-date to))}))}}]

    ["/collections"
     {:get {:summary    "Collections activity performance by month"
            :tags       ["Reports"]
            :parameters {:query schemas/DateRangeQuery}
            :handler    (fn [{:keys [identity tenant-id query-params]}]
                          (rbac/require-permission identity :collection/read)
                          (let [{:keys [from to]} query-params]
                            {:status 200
                             :body   (loans-db/collections-performance
                                       ds tenant-id
                                       (parse-date from)
                                       (parse-date to))}))}}]]])
