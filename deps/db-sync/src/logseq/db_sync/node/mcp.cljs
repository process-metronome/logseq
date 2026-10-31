(ns logseq.db-sync.node.mcp
  "MCP (Model Context Protocol) Streamable HTTP endpoint for the self-hosted
  node adapter. Exposes every semantic REST operation (/api/v1/...) as an
  MCP tool, mirroring the Cloudflare worker's /mcp surface
  (worker/semantic_api_tools.mjs) but as a stateless JSON-RPC POST endpoint
  so it runs on plain Node without the codemode SDK."
  (:require [clojure.string :as string]
            [logseq.db-sync.common :as common]
            [logseq.db-sync.platform.core :as platform]
            [logseq.db-sync.worker.auth :as auth]
            [logseq.db-sync.worker.dispatch :as worker-dispatch]
            [logseq.db-sync.worker.routes.semantic :as semantic-routes]
            [promesa.core :as p]))

(def ^:private protocol-version "2025-06-18")

(def ^:private semantic-http-methods
  #{"get" "post" "put" "patch" "delete"})

(defn- resolve-refs
  "Resolve local `#/...` $ref pointers in an OpenAPI document (cljs data)."
  ([value root] (resolve-refs value root (atom {}) #{}))
  ([value root cache seen]
   (cond
     (map? value)
     (if-let [ref (and (string? (:$ref value)) (:$ref value))]
       (let [cached (get @cache ref ::missing)]
         (if (not= ::missing cached)
           cached
           (if (contains? seen ref)
             {}
             (let [resolved (reduce (fn [acc part]
                                      (let [part (-> part
                                                     (string/replace "~1" "/")
                                                     (string/replace "~0" "~"))]
                                        (or (get acc part)
                                            (get acc (keyword part)))))
                                    root
                                    (string/split (subs ref 2) #"/"))
                   out (resolve-refs resolved root cache (conj seen ref))]
               (swap! cache assoc ref out)
               out))))
       (into (empty value) (map (fn [[k v]] [k (resolve-refs v root cache seen)]) value)))
     (sequential? value) (mapv #(resolve-refs % root cache seen) value)
     :else value)))

(defn- sanitize-tool-name [operation-id]
  (let [sanitized (string/replace operation-id #"[^a-zA-Z0-9_-]" "_")]
    (subs sanitized 0 (min 64 (count sanitized)))))

(defn build-tools
  "One MCP tool per OpenAPI operation, named after its operationId."
  [spec]
  (let [resolved (resolve-refs spec spec)
        used-names (atom #{})
        tools (atom [])
        index (atom {})]
    (doseq [[path path-item] (get resolved :paths)
            [method op] path-item
            :when (and (map? op)
                       (contains? semantic-http-methods (name method)))
            :let [operation-id (:operationId op)]
            :when (string? operation-id)]
      (let [name (loop [candidate (sanitize-tool-name operation-id)]
                   (if (contains? @used-names candidate)
                     (recur (str candidate "_"))
                     candidate))
            parameters (or (:parameters op) [])
            path-params (filterv #(= "path" (:in %)) parameters)
            query-params (filterv #(= "query" (:in %)) parameters)
            param-schema (fn [p]
                           (cond-> (or (:schema p) {:type "string"})
                             (:description p) (assoc :description (:description p))))
            properties (into {}
                             (map (fn [p] [(:name p) (param-schema p)]))
                             (concat path-params query-params))
            request-body (:requestBody op)
            body-schema (or (get-in request-body [:content "application/json" :schema])
                            (get-in request-body [:content "application/octet-stream" :schema])
                            {:type "object"})
            properties (if request-body
                         (assoc properties "body" body-schema)
                         properties)
            required (cond-> (vec (keep #(when (or (= "path" (:in %)) (true? (:required %)))
                                                  (:name %))
                                        parameters))
                       (and request-body (true? (:required request-body)))
                       (conj "body"))
            method-name (string/upper-case (name method))
            tool {:name name
                  :description (or (:summary op)
                                   (:description op)
                                   (str method-name " " path))
                  :inputSchema (cond-> {:type "object"
                                        :properties properties
                                        :additionalProperties false}
                                 (seq required) (assoc :required required))
                  :annotations {:readOnlyHint (= "GET" method-name)
                                :openWorldHint true
                                :destructiveHint (= "DELETE" method-name)
                                :idempotentHint (contains? #{"GET" "PUT" "DELETE" "HEAD"} method-name)}}]
        (swap! used-names conj name)
        (swap! tools conj tool)
        (swap! index assoc name {:method method-name
                                 :path path
                                 :path-params (mapv :name path-params)})))
    {:tools @tools :index @index}))

(def ^:private tools-by-issuer (atom {}))

(defn- get-tools [env]
  (let [issuer (or (aget env "COGNITO_ISSUER") "")
        cached (get @tools-by-issuer issuer ::missing)]
    (if (not= ::missing cached)
      cached
      (let [built (build-tools (semantic-routes/openapi-document issuer))]
        (swap! tools-by-issuer assoc issuer built)
        built))))

(defn- <call-tool
  "Run one MCP tool by forwarding it as a semantic REST request through the
  worker dispatch (auth, PAT graph scoping, rate limits and readonly checks
  all apply unchanged)."
  [request env tool-name ^js arguments {:keys [index]}]
  (if-let [{:keys [method path path-params]} (get index tool-name)]
    (let [args (or arguments #js {})
          substituted (reduce (fn [p param]
                                (string/replace p
                                                (str ":" param)
                                                (js/encodeURIComponent (str (or (aget args param) "")))))
                              path
                              path-params)
          path-param-set (set path-params)
          query (->> (js/Object.keys args)
                     (remove #(or (contains? path-param-set %) (= "body" %)))
                     (map (fn [k]
                            (str (js/encodeURIComponent k)
                                 "="
                                 (js/encodeURIComponent (str (aget args k))))))
                     (string/join "&"))
          body (when (aget args "body")
                 (js/JSON.stringify (aget args "body")))
          url (str (.-origin (platform/request-url request))
                   substituted
                   (when (seq query) (str "?" query)))
          headers (doto (js-obj)
                    (aset "authorization" (.get (.-headers request) "authorization"))
                    (when body
                      (aset "content-type" "application/json")))
          new-request (platform/request url #js {:method method
                                                 :headers headers
                                                 :body body})]
      (p/let [response (worker-dispatch/handle-worker-fetch new-request env)
              status (.-status response)
              text (if (= 204 status) "" (p/let [t (.text response)] t))]
        {:content [{:type "text" :text (if (seq text) text (str "status " status))}]
         :isError (>= status 400)}))
    {:content [{:type "text" :text (str "unknown tool: " tool-name)}]
     :isError true}))

(defn- <dispatch-method [request env method params tools]
  (case method
    "initialize"
    (p/resolved {:result {:protocolVersion (or (:protocolVersion params) protocol-version)
                          :capabilities {:tools {:listChanged false}}
                          :serverInfo {:name "logseq-db-sync" :version "1.0.0"}}})
    "ping"
    (p/resolved {:result {}})
    "tools/list"
    (p/resolved {:result {:tools (:tools tools)}})
    "tools/call"
    (p/let [tool-name (:name params)
            result (if (string? tool-name)
                     (<call-tool request env tool-name (:arguments params) tools)
                     {:content [{:type "text" :text "missing tool name"}] :isError true})]
      (p/resolved {:result result}))
    "resources/list"
    (p/resolved {:result {:resources []}})
    "prompts/list"
    (p/resolved {:result {:prompts []}})
    (p/resolved {:error {:code -32601 :message (str "method not found: " method)}})))

(defn- <handle-message [request env tools {:keys [method params] :as message}]
  (if-not (contains? message :id)
    (p/resolved nil) ;; JSON-RPC notification: no response
    (let [id (:id message)]
      (p/let [out (<dispatch-method request env method (or params {}) tools)]
        (if-let [error (:error out)]
          {:jsonrpc "2.0" :id id :error error}
          {:jsonrpc "2.0" :id id :result (:result out)})))))

(defn- mcp-response [payload status]
  (platform/response (common/json-stringify payload)
                     #js {:status status
                          :headers #js {"content-type" "application/json"}}))

(defn- <handle-post [request env]
  (p/let [claims (auth/semantic-auth-claims request env)]
    (if (nil? claims)
      (platform/response (common/json-stringify {:error "unauthorized"})
                         #js {:status 401
                              :headers #js {"content-type" "application/json"
                                            "www-authenticate" "Bearer"}})
      (p/let [body (common/read-json request)
              tools (get-tools env)
              payload (when body (js->clj body :keywordize-keys true))]
        (cond
          (nil? payload)
          (mcp-response {:jsonrpc "2.0" :id nil :error {:code -32700 :message "parse error"}} 400)

          (vector? payload)
          (p/let [all-responses (p/all (mapv #(<handle-message request env tools %) payload))
                  responses (filterv some? all-responses)]
            (if (seq responses)
              (mcp-response responses 200)
              (platform/response nil #js {:status 202
                                          :headers #js {"content-type" "application/json"}})))

          (map? payload)
          (p/let [response (<handle-message request env tools payload)]
            (if (some? response)
              (mcp-response response 200)
              (platform/response nil #js {:status 202
                                          :headers #js {"content-type" "application/json"}})))

          :else
          (mcp-response {:jsonrpc "2.0" :id nil :error {:code -32600 :message "invalid request"}} 400)))))

(defn handle
  "Stateless MCP Streamable HTTP endpoint: POST /mcp with JSON-RPC 2.0.
  GET/DELETE return 405 (no standalone SSE stream)."
  [request env]
  (if (= "POST" (.-method request))
    (<handle-post request env)
    (platform/response "MCP endpoint: POST JSON-RPC 2.0 (tools/list, tools/call)"
                       #js {:status 405
                            :headers #js {"content-type" "text/plain"
                                          "allow" "POST"}})))
