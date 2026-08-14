(ns loanmanager.db.users
  (:require [next.jdbc :as jdbc]
            [honey.sql :as sql]
            [loanmanager.db.connection :as db]))

(defn find-by-id [ds tenant-id id]
  (jdbc/execute-one! ds
    (sql/format {:select [:u.id :u.email :u.full-name :u.role-id :u.active
                          :u.tenant-id :u.branch-id :u.created-at :u.updated-at
                          [:r.name :role-name] [:r.permissions :permissions]]
                 :from   [[:users :u]]
                 :join   [[:roles :r] [:= :u.role-id :r.id]]
                 :where  [:and [:= :u.tenant-id tenant-id] [:= :u.id id]]})))

(defn find-by-email [ds email]
  (jdbc/execute-one! ds
    (sql/format {:select [:u.* [:r.name :role-name] [:r.permissions :permissions]]
                 :from   [[:users :u]]
                 :join   [[:roles :r] [:= :u.role-id :r.id]]
                 :where  [:= :u.email email]})))

(defn list-users [ds tenant-id {:keys [role active limit offset]
                                 :or   {limit 20 offset 0}}]
  (jdbc/execute! ds
    (sql/format (cond-> {:select   [:u.id :u.email :u.full-name :u.active
                                    :u.created-at [:r.name :role-name]]
                          :from     [[:users :u]]
                          :join     [[:roles :r] [:= :u.role-id :r.id]]
                          :where    [:= :u.tenant-id tenant-id]
                          :order-by [[:u.created-at :desc]]
                          :limit    limit
                          :offset   offset}
                  role   (update :where conj [:= :r.name role])
                  (some? active) (update :where conj [:= :u.active active])))))

(defn create! [ds user]
  (db/execute-one! ds
    (sql/format {:insert-into :users
                 :values      [user]
                 :returning   [:id :email :full-name :active :tenant-id :role-id :branch-id :created-at]})))

(defn update! [ds tenant-id id changes]
  (db/execute-one! ds
    (sql/format {:update    :users
                 :set       (assoc changes :updated-at [:now])
                 :where     [:and [:= :tenant-id tenant-id] [:= :id id]]
                 :returning [:id :email :full-name :active :role-id :updated-at]})))
