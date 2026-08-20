(ns loanmanager.security.reset-token
  "Password-reset token generation/hashing. Only the SHA-256 hash is ever
   persisted (see the password_reset_tokens migration) — unlike passwords,
   these are already 256 bits of CSPRNG randomness, so a fast hash is fine:
   brute-forcing the token is infeasible regardless of hash speed, and a
   slow/salted hash would only add cost without adding security here."
  (:import [java.security SecureRandom MessageDigest]
           [java.util Base64]))

(defn generate
  "A URL-safe, 256-bit random token."
  []
  (let [bytes (byte-array 32)]
    (.nextBytes (SecureRandom.) bytes)
    (.encodeToString (Base64/getUrlEncoder) bytes)))

(defn hash-token [token]
  (let [digest (MessageDigest/getInstance "SHA-256")]
    (->> (.digest digest (.getBytes ^String token "UTF-8"))
         (map #(format "%02x" %))
         (apply str))))
