(ns logseq.db-sync.worker.auth
  (:require [clojure.string :as string]
            [logseq.common.authorization :as authorization]
            [logseq.db-sync.common :as common]
            [logseq.db-sync.index :as index]
            [promesa.core :as p]))

(def personal-access-token-prefix "logseq_pat_")

(defn- bearer-token [auth-header]
  (when (and (string? auth-header) (string/starts-with? auth-header "Bearer "))
    (subs auth-header 7)))

(defn token-from-request [request]
  (or (bearer-token (.get (.-headers request) "authorization"))
      (let [url (js/URL. (.-url request))]
        (.get (.-searchParams url) "token"))))

(defn- decode-jwt-part [part]
  (let [pad (if (pos? (mod (count part) 4))
              (apply str (repeat (- 4 (mod (count part) 4)) "="))
              "")
        base64 (-> (str part pad)
                   (string/replace "-" "+")
                   (string/replace "_" "/"))
        raw (js/atob base64)]
    (js/JSON.parse raw)))

(defn unsafe-jwt-claims [token]
  (try
    (when (string? token)
      (let [parts (string/split token #"\.")]
        (when (= 3 (count parts))
          (decode-jwt-part (nth parts 1)))))
    (catch :default _
      nil)))

(def ^:private recoverable-auth-errors
  #{"invalid" "iss not found" "aud not found" "exp" "kid"})

(defn- recoverable-auth-error?
  [error]
  (when error
    (let [message (or (ex-message error) (some-> error .-message))]
      (contains? recoverable-auth-errors message))))

(defn- allow-unverified-jwt-claims?
  [env]
  (common/env-flag-enabled? env "DB_SYNC_ALLOW_UNVERIFIED_JWT_CLAIMS"))

(defn- static-token-claims
  "Self-hosted local auth: when `DB_SYNC_STATIC_TOKEN` is set on the env, an
  exact bearer-token match authenticates as `DB_SYNC_STATIC_USER_ID`
  (default \"local-user\") with full read+write scope. Inert unless the env
  var is set, so the hosted worker is unaffected."
  [token env]
  (let [expected (aget env "DB_SYNC_STATIC_TOKEN")]
    (when (and (string? expected) (seq expected) (= expected token))
      (let [user-id (let [v (aget env "DB_SYNC_STATIC_USER_ID")]
                      (if (and (string? v) (seq v)) v "local-user"))]
        #js {"sub" user-id
             "scope" "logseq/read logseq/write"
             "static-token" true}))))

(defn- expired-token?
  [token]
  (when-let [claims (unsafe-jwt-claims token)]
    (let [exp (aget claims "exp")
          now-s (js/Math.floor (/ (.now js/Date) 1000))]
      (and (number? exp)
           (<= exp now-s)))))

(defn auth-claims [request env]
  (let [token (token-from-request request)]
    (if (string? token)
      (if-let [static-claims (static-token-claims token env)]
        (p/resolved static-claims)
        (if (expired-token? token)
          (p/resolved nil)
          (-> (authorization/verify-jwt token env)
              (p/catch (fn [error]
                         (cond
                           (recoverable-auth-error? error)
                           nil

                           (allow-unverified-jwt-claims? env)
                           (unsafe-jwt-claims token)

                           :else
                           (p/rejected error)))))))
      (p/resolved nil))))

(defn personal-access-token?
  [token]
  (and (string? token)
       (string/starts-with? token personal-access-token-prefix)))

(defn <sha-256-hex
  [value]
  (p/let [payload (.encode (js/TextEncoder.) value)
          digest (.digest (.-subtle js/crypto) "SHA-256" payload)]
    (->> (array-seq (js/Uint8Array. digest))
         (map (fn [octet]
                (.padStart (.toString octet 16) 2 "0")))
         (apply str))))

(defn- permission->scope
  [permission]
  (case permission
    "read" "logseq/read"
    "write" "logseq/write"
    "both" "logseq/read logseq/write"
    nil))

(defn semantic-auth-claims
  [request env]
  (let [token (token-from-request request)]
    (if (personal-access-token? token)
      (if-let [db (aget env "DB")]
        (p/let [token-hash (<sha-256-hex token)
                pat (index/<personal-access-token-by-hash db token-hash)
                scope (some-> pat :permission permission->scope)]
          (when (and pat
                     scope
                     (> (:expires-at pat) (common/now-ms)))
            #js {"sub" (:user-id pat)
                 "scope" scope
                 "pat_id" (:id pat)
                 "pat_graph_id" (:graph-id pat)}))
        (p/resolved nil))
      (auth-claims request env))))
