(ns loanmanager.security.token-store
  "JWT access-token blacklist, DB-backed via loanmanager.db.tokens (the
   token_blacklist table, jti VARCHAR(100) — added in migration 008-gaps but
   never actually queried until now). Uses the :jti claim for per-token
   revocation — not :sub, since a user can hold several concurrently-valid
   tokens.

   Previously this was an in-memory atom that reset on every restart or
   redeploy, silently un-revoking every blacklisted token despite this
   namespace's own docstring claiming DB-backing already existed."
  (:require [loanmanager.db.tokens :as tokens-db]))

(defn blacklisted? [ds jti]
  (boolean (and jti (tokens-db/blacklisted? ds jti))))

(defn blacklist-token!
  "Blacklist a decoded claims map by its :jti until :exp (epoch seconds, as
   produced by buddy.sign.jwt/unsign)."
  [ds {:keys [jti exp]}]
  (when jti
    (tokens-db/blacklist! ds jti (java.sql.Timestamp. (* (long exp) 1000)))))
