(ns loanmanager.security.mfa
  "TOTP (RFC 6238) multi-factor auth. users.mfa_secret existed as a column
   from the very first migration but nothing ever generated, stored, or
   verified against it — this is the whole enrollment + verification flow,
   implemented directly against RFC 4226/6238 (HMAC-SHA1, 30s step, 6
   digits) with no external TOTP library, to avoid pulling in a dependency
   for ~60 lines of well-specified math."
  (:import [javax.crypto Mac]
           [javax.crypto.spec SecretKeySpec]))

(def ^:private step-seconds 30)
(def ^:private digits 6)
(def ^:private base32-alphabet "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567")

(defn generate-secret
  "Random 20-byte secret, base32-encoded (no padding) for display / QR.
   Standard bit-buffer packing: shift each byte's 8 bits in, drain 5-bit
   groups out to base32 chars as they accumulate."
  []
  (let [raw (byte-array 20)]
    (.nextBytes (java.security.SecureRandom.) raw)
    (let [sb (StringBuilder.)
          {:keys [buffer bits]}
          (reduce (fn [{:keys [buffer bits]} b]
                    (loop [buffer (bit-or (bit-shift-left buffer 8) (bit-and (long b) 0xFF))
                           bits   (+ bits 8)]
                      (if (>= bits 5)
                        (do
                          (.append sb (.charAt base32-alphabet (bit-and (bit-shift-right buffer (- bits 5)) 0x1F)))
                          (recur buffer (- bits 5)))
                        {:buffer buffer :bits bits})))
                  {:buffer 0 :bits 0}
                  raw)]
      (when (pos? bits)
        (.append sb (.charAt base32-alphabet (bit-and (bit-shift-left buffer (- 5 bits)) 0x1F))))
      (str sb))))

(defn- base32-decode ^bytes [secret]
  (let [clean (-> secret (.toUpperCase) (.replace "=" ""))
        out   (java.io.ByteArrayOutputStream.)]
    (reduce (fn [{:keys [buffer bits]} c]
              (let [idx (.indexOf base32-alphabet (int c))]
                (when (neg? idx) (throw (ex-info "Invalid base32 MFA secret" {:type :validation})))
                (loop [buffer (bit-or (bit-shift-left buffer 5) idx)
                       bits   (+ bits 5)]
                  (if (>= bits 8)
                    (do
                      (.write out (bit-and (bit-shift-right buffer (- bits 8)) 0xFF))
                      (recur buffer (- bits 8)))
                    {:buffer buffer :bits bits}))))
            {:buffer 0 :bits 0}
            clean)
    (.toByteArray out)))

(defn- hotp [^bytes secret-bytes ^long counter]
  (let [counter-bytes (byte-array 8)]
    (loop [i 7 c counter]
      (when (>= i 0)
        (aset counter-bytes i (unchecked-byte (bit-and c 0xFF)))
        (recur (dec i) (bit-shift-right c 8))))
    (let [mac (doto (Mac/getInstance "HmacSHA1")
                (.init (SecretKeySpec. secret-bytes "HmacSHA1")))
          hash (.doFinal mac counter-bytes)
          offset (bit-and (aget hash 19) 0xF)
          truncated (bit-or (bit-shift-left (bit-and (aget hash offset) 0x7F) 24)
                             (bit-shift-left (bit-and (aget hash (+ offset 1)) 0xFF) 16)
                             (bit-shift-left (bit-and (aget hash (+ offset 2)) 0xFF) 8)
                             (bit-and (aget hash (+ offset 3)) 0xFF))]
      (mod truncated (long (Math/pow 10 digits))))))

(defn current-code
  ([secret] (current-code secret (System/currentTimeMillis)))
  ([secret now-ms]
   (let [counter (quot (quot now-ms 1000) step-seconds)]
     (format (str "%0" digits "d") (hotp (base32-decode secret) counter)))))

(defn valid-code?
  "Accepts the current 30s window plus one step of clock skew either way.
   Always returns a real boolean (never nil) — callers may write this
   straight into a NOT NULL boolean column."
  [secret code]
  (boolean
    (and secret code
         (some #(= code (current-code secret (+ (System/currentTimeMillis) (* % step-seconds 1000))))
               [-1 0 1]))))

(defn otpauth-uri [secret email issuer]
  (str "otpauth://totp/" (java.net.URLEncoder/encode (str issuer ":" email) "UTF-8")
       "?secret=" secret
       "&issuer=" (java.net.URLEncoder/encode issuer "UTF-8")
       "&algorithm=SHA1&digits=" digits "&period=" step-seconds))
