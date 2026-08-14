(ns loanmanager.api.routes.reports
  (:require [loanmanager.api.schemas :as schemas]
            [loanmanager.db.loans :as loans-db]
            [loanmanager.api.csv :as csv]
            [loanmanager.security.rbac :as rbac]))

(defn- parse-date [s] (java.time.LocalDate/parse s))

(defn- respond
  "Regulatory/portfolio reports routinely need to leave the system as a
   spreadsheet — pass ?format=csv to get one instead of JSON."
  [query-params rows filename]
  (if (= "csv" (:format query-params))
    (csv/csv-response rows filename)
    {:status 200 :body rows}))

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
                          (let [{:keys [from to]} query-params
                                rows (loans-db/disbursements-by-period
                                       ds tenant-id (parse-date from) (parse-date to))]
                            (respond query-params rows "disbursements.csv")))}}]

    ["/income"
     {:get {:summary    "Interest income and principal collected by month"
            :tags       ["Reports"]
            :parameters {:query schemas/DateRangeQuery}
            :handler    (fn [{:keys [identity tenant-id query-params]}]
                          (rbac/require-permission identity :ledger/read)
                          (let [{:keys [from to]} query-params
                                rows (loans-db/income-by-period
                                       ds tenant-id (parse-date from) (parse-date to))]
                            (respond query-params rows "income.csv")))}}]

    ["/collections"
     {:get {:summary    "Collections activity performance by month"
            :tags       ["Reports"]
            :parameters {:query schemas/DateRangeQuery}
            :handler    (fn [{:keys [identity tenant-id query-params]}]
                          (rbac/require-permission identity :collection/read)
                          (let [{:keys [from to]} query-params
                                rows (loans-db/collections-performance
                                       ds tenant-id (parse-date from) (parse-date to))]
                            (respond query-params rows "collections.csv")))}}]]])
