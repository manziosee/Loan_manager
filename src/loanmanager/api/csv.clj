(ns loanmanager.api.csv
  "Minimal CSV rendering for report endpoints — regulatory/portfolio reports
   need to leave the system as a spreadsheet, not just JSON a human has to
   convert by hand."
  (:require [clojure.string :as str]))

(defn- escape [v]
  (let [s (str v)]
    (if (re-find #"[,\"\n]" s)
      (str "\"" (str/replace s "\"" "\"\"") "\"")
      s)))

(defn ->csv
  "Renders a seq of flat maps as CSV text. Column order follows the keys of
   the first row; keys may be namespace-qualified (:loans/status) or not —
   only the name is used for the header."
  [rows]
  (if (empty? rows)
    ""
    (let [cols   (keys (first rows))
          header (str/join "," (map name cols))
          lines  (map (fn [row] (str/join "," (map #(escape (get row %)) cols))) rows)]
      (str/join "\n" (cons header lines)))))

(defn csv-response
  [rows filename]
  {:status  200
   :headers {"Content-Type"        "text/csv; charset=utf-8"
             "Content-Disposition" (str "attachment; filename=\"" filename "\"")}
   :body    (->csv rows)})
