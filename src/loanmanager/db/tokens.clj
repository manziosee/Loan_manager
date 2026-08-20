(ns loanmanager.db.tokens
  "Durable JWT lifecycle tracking: access-token blacklist (token_blacklist)
   and refresh-token issuance/revocation (refresh_tokens). See
   loanmanager.security.token-store for why this replaced an in-memory atom."
  (:require [next.jdbc :as jdbc]
            [honey.sql :as sql]
            [loanmanager.db.connection :as db]))

;; ── Access-token blacklist ───────────────────────────────────────────────────
;; token_blacklist (jti VARCHAR(100)) already existed — added in migration
;; 008-gaps but never actually queried until now.

(defn blacklist! [ds jti expires-at]
  (db/execute-one! ds
    (sql/format {:insert-into   :token-blacklist
                 :values        [{:jti jti :expires-at expires-at}]
                 :on-conflict   [:jti]
                 :do-nothing    true})))

(defn blacklisted? [ds jti]
  (some? (jdbc/execute-one! ds
           (sql/format {:select [:jti]
                        :from   [:token-blacklist]
                        :where  [:and [:= :jti jti] [:> :expires-at [:now]]]}))))

;; ── Refresh-token issuance/revocation ────────────────────────────────────────
;; refresh_tokens (id, user_id, token_hash, expires_at, ...) already existed
;; from 001-auth — never written to or queried until now. Looked up by a hash
;; of the refresh token string itself (loanmanager.security.reset-token/
;; hash-token), the same pattern as password-reset tokens: no jti/claims
;; parsing needed to check revocation, and a leaked DB row can't be replayed
;; as a token.

(defn issue-refresh! [ds {:keys [user-id token-hash expires-at]}]
  (db/execute-one! ds
    (sql/format {:insert-into :refresh-tokens
                 :values      [{:user-id user-id :token-hash token-hash
                                 :expires-at expires-at}]
                 :returning   [:id]})))

(defn refresh-valid? [ds token-hash]
  (some? (jdbc/execute-one! ds
           (sql/format {:select [:id]
                        :from   [:refresh-tokens]
                        :where  [:and [:= :token-hash token-hash]
                                      [:is :revoked-at nil]
                                      [:> :expires-at [:now]]]}))))

(defn revoke-refresh! [ds token-hash]
  (db/execute-one! ds
    (sql/format {:update :refresh-tokens
                 :set    {:revoked-at [:now]}
                 :where  [:= :token-hash token-hash]})))

(defn revoke-all-refresh-for-user! [ds user-id]
  (jdbc/execute! ds
    (sql/format {:update :refresh-tokens
                 :set    {:revoked-at [:now]}
                 :where  [:and [:= :user-id user-id] [:is :revoked-at nil]]})))
