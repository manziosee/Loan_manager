(ns loanmanager.db.roles
  (:require [next.jdbc :as jdbc]
            [honey.sql :as sql]))

(defn find-by-id-and-tenant
  "Scoped role lookup — used to validate a role-id supplied by a client
   actually belongs to their own tenant before it's assigned to a user.
   Without this, POST/PUT /users accepted any role-id verbatim, letting a
   tenant admin assign a role UUID from a different tenant (including a
   future platform-admin role) to a user in their own tenant."
  [ds tenant-id role-id]
  (jdbc/execute-one! ds
    (sql/format {:select [:id]
                 :from   [:roles]
                 :where  [:and [:= :tenant-id tenant-id] [:= :id role-id]]})))
