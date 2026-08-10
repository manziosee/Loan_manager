(ns loanmanager.db.notifications
  (:require [next.jdbc :as jdbc]
            [honey.sql :as sql]
            [loanmanager.db.connection :as db]))

(defn list-for-user [ds user-id {:keys [unread-only limit offset]
                                  :or   {limit 50 offset 0}}]
  (jdbc/execute! ds
    (sql/format (cond-> {:select   [:*]
                          :from     [:notifications]
                          :where    [:= :user-id user-id]
                          :order-by [[:created-at :desc]]
                          :limit    limit
                          :offset   offset}
                  unread-only (update :where conj [:= :read false])))))

(defn mark-read! [ds user-id id]
  (db/execute-one! ds
    (sql/format {:update    :notifications
                 :set       {:read true :read-at [:now]}
                 :where     [:and [:= :id id] [:= :user-id user-id]]
                 :returning [:*]})))

(defn create! [ds notification]
  (db/execute-one! ds
    (sql/format {:insert-into :notifications
                 :values      [notification]
                 :returning   [:*]})))

(defn unread-count [ds user-id]
  (-> (jdbc/execute-one! ds
        (sql/format {:select [[[:count :id] :count]]
                     :from   [:notifications]
                     :where  [:and [:= :user-id user-id] [:= :read false]]}))
      :count
      (or 0)))
