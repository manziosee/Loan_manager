(ns loanmanager.security.middleware
  (:require [clojure.tools.logging :as log]
            [loanmanager.security.jwt :as jwt]
            [loanmanager.security.token-store :as token-store]))

;; ── Auth middleware ────────────────────────────────────────────────────────────
;; Login rate limiting lives in loanmanager.db.security (DB-backed, via the
;; login_attempts table) since it needs to survive restarts and work across
;; more than one app instance — see routes/auth.clj for where it's applied.

(defn wrap-authentication [handler config]
  (fn [request]
    (let [auth-header (get-in request [:headers "authorization"])
          token       (when (and auth-header (.startsWith auth-header "Bearer "))
                        (subs auth-header 7))
          identity    (when token (jwt/token->identity token config))
          jti         (get-in identity [:claims :jti])
          identity    (when (and identity (not (token-store/blacklisted? jti)))
                        identity)]
      (handler (assoc request :identity identity :raw-token token)))))

(defn wrap-require-auth [handler]
  (fn [request]
    (if (:identity request)
      (handler request)
      {:status 401 :body {:error "Unauthorized" :message "Valid Bearer token required"}})))

(defn wrap-tenant
  "Injects tenant-id from identity into request for data isolation."
  [handler]
  (fn [request]
    (handler (assoc request :tenant-id (get-in request [:identity :tenant-id])))))

(defn wrap-exception [handler]
  (fn [request]
    (try
      (handler request)
      (catch clojure.lang.ExceptionInfo e
        (let [{:keys [type] :as data} (ex-data e)]
          (case type
            :forbidden  {:status 403 :body {:error "Forbidden"       :message (.getMessage e)}}
            :not-found  {:status 404 :body {:error "Not Found"       :message (.getMessage e)}}
            :validation {:status 422 :body {:error "Validation Error" :details data}}
            (do (log/error e "Unhandled ExceptionInfo" data)
                {:status 500 :body {:error "Internal Server Error"}}))))
      (catch Exception e
        (log/error e "Unhandled exception")
        {:status 500 :body {:error "Internal Server Error"}}))))

(defn wrap-request-log [handler]
  (fn [request]
    (let [start    (System/currentTimeMillis)
          response (handler request)
          elapsed  (- (System/currentTimeMillis) start)]
      (log/info (format "%s %s %d %dms"
                        (name (:request-method request))
                        (:uri request)
                        (:status response)
                        elapsed))
      response)))

(defn wrap-security-headers [handler]
  (fn [request]
    (-> (handler request)
        (update :headers merge
                {"X-Content-Type-Options"    "nosniff"
                 "X-Frame-Options"           "DENY"
                 "X-XSS-Protection"          "1; mode=block"
                 "Strict-Transport-Security" "max-age=31536000; includeSubDomains"
                 "Content-Security-Policy"   "default-src 'self'"}))))

(defn wrap-coerced-query-params
  "Reitit's coerce-request-middleware writes coerced query params to
   (:parameters request) :query only — it never rewrites the raw, string-keyed
   :query-params ring produces from the URL. Handlers across this codebase
   destructure :query-params directly expecting coerced/keyword-keyed values,
   so backfill it here once, centrally, instead of touching every handler.
   Must run after reitit's coerce-request-middleware in the chain."
  [handler]
  (fn [request]
    (let [coerced-query (get-in request [:parameters :query])]
      (handler (cond-> request
                 coerced-query (assoc :query-params coerced-query))))))

(defn client-ip [request]
  (or (get-in request [:headers "x-forwarded-for"])
      (get-in request [:headers "x-real-ip"])
      (some-> request :remote-addr)))
