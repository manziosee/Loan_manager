(ns loanmanager.security.crypto
  "Symmetric AES-GCM encrypt/decrypt for sensitive columns at rest — first
   use is users.mfa_secret, which was previously stored as the raw TOTP
   secret. Unlike security/reset-token's one-way hash (fine for a value we
   only ever compare, never need back), MFA verification needs the
   plaintext secret back, so this is reversible, keyed by a server-side
   key (resources/config.edn :security :encryption-key) that never leaves
   the backend."
  (:require [clojure.string :as str])
  (:import [java.security SecureRandom]
           [javax.crypto Cipher KeyGenerator SecretKey]
           [javax.crypto.spec GCMParameterSpec SecretKeySpec]
           [java.util Base64]))

(def ^:private algo "AES/GCM/NoPadding")
(def ^:private iv-len-bytes 12)
(def ^:private tag-len-bits 128)

(defn generate-key
  "For provisioning a new ENCRYPTION_KEY — not called at runtime."
  []
  (-> (doto (KeyGenerator/getInstance "AES") (.init 256))
      .generateKey .getEncoded
      (->> (.encodeToString (Base64/getEncoder)))))

(defn- key-from-b64 ^SecretKey [key-b64]
  (SecretKeySpec. (.decode (Base64/getDecoder) ^String key-b64) "AES"))

(defn encrypt
  "Returns a base64 string: IV || ciphertext(+tag). Throws if key-b64 is nil
   or blank — callers must fail loudly rather than silently store plaintext."
  [key-b64 ^String plaintext]
  (when (str/blank? key-b64)
    (throw (ex-info "ENCRYPTION_KEY is not configured — refusing to store a secret unencrypted" {})))
  (let [iv     (byte-array iv-len-bytes)
        _      (.nextBytes (SecureRandom.) iv)
        cipher (doto (Cipher/getInstance algo)
                 (.init Cipher/ENCRYPT_MODE (key-from-b64 key-b64) (GCMParameterSpec. tag-len-bits iv)))
        ct     (.doFinal cipher (.getBytes plaintext "UTF-8"))]
    (.encodeToString (Base64/getEncoder) (byte-array (concat iv ct)))))

(defn decrypt
  [key-b64 ^String encoded]
  (let [raw    (.decode (Base64/getDecoder) encoded)
        iv     (byte-array (take iv-len-bytes raw))
        ct     (byte-array (drop iv-len-bytes raw))
        cipher (doto (Cipher/getInstance algo)
                 (.init Cipher/DECRYPT_MODE (key-from-b64 key-b64) (GCMParameterSpec. tag-len-bits iv)))]
    (String. (.doFinal cipher ct) "UTF-8")))
