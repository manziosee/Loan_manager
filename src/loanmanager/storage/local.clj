(ns loanmanager.storage.local
  "Local-disk file storage for KYC documents. No cloud storage credentials
   (S3 or otherwise) are available in this environment, so this is a real,
   working implementation against the filesystem rather than a stub —
   store!/resolve-path is the whole contract, so swapping in an
   S3-compatible client later doesn't touch any caller."
  (:require [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.util UUID]))

(def ^:private max-upload-bytes (* 10 1024 1024))

(defn- safe-filename
  "Never trust the client's filename for the on-disk path — path traversal
   (../../etc/passwd) and collisions are both real risks."
  [original]
  (-> (or original "file")
      (str/replace #"[^a-zA-Z0-9._-]" "_")))

(defn store!
  "Copies an uploaded tempfile into upload-dir/tenant-id/customer-id/ under a
   random-prefixed name. Returns the relative storage key to persist in
   customer_documents.file_url."
  [upload-dir {:keys [tenant-id customer-id tempfile filename size]}]
  (when (and size (> size max-upload-bytes))
    (throw (ex-info "File exceeds maximum upload size (10MB)" {:type :validation})))
  (let [dir  (io/file upload-dir (str tenant-id) (str customer-id))
        key  (str (UUID/randomUUID) "-" (safe-filename filename))
        dest (io/file dir key)]
    (.mkdirs dir)
    (io/copy tempfile dest)
    (str tenant-id "/" customer-id "/" key)))

(defn resolve-path [upload-dir storage-key]
  (io/file upload-dir storage-key))
