(ns logseq.db-sync.node-mcp-test
  (:require [cljs.test :refer [async deftest is testing]]
            [clojure.string :as string]
            [logseq.db-sync.node.dispatch :as node-dispatch]
            [logseq.db-sync.node.mcp :as mcp]
            [logseq.db-sync.worker.dispatch :as worker-dispatch]
            [logseq.db-sync.worker.routes.semantic :as semantic-routes]
            [promesa.core :as p]))

(defn- deep-contains-key? [value k]
  (cond
    (map? value) (or (contains? value k)
                     (some #(deep-contains-key? % k) (vals value)))
    (sequential? value) (some #(deep-contains-key? % k) value)
    :else false))

(deftest build-tools-covers-all-operations-test
  (let [{:keys [tools index]} (mcp/build-tools (semantic-routes/openapi-document ""))]
    (is (= (count semantic-routes/operations) (count tools)))
    (is (= (count tools) (count index)))
    (doseq [tool tools]
      (is (string? (:name tool)))
      (is (map? (:inputSchema tool)))
      (is (not (deep-contains-key? (:inputSchema tool) :$ref))
          (str "unresolved $ref in inputSchema of " (:name tool))))
    (let [get-page (get index "getPage")]
      (is (= "GET" (:method get-page)))
      (is (= ["graph-id" "page-id"] (:path-params get-page))))
    (let [list-pages (some #(when (= "listPages" (:name %)) %) tools)]
      (is (true? (get-in list-pages [:annotations :readOnlyHint])))
      (is (contains? (get-in list-pages [:inputSchema :properties]) "graph-id"))
      (is (contains? (get-in list-pages [:inputSchema :required]) "graph-id")))
    (let [create-page (some #(when (= "createPage" (:name %)) %) tools)]
      (is (not (true? (get-in create-page [:annotations :readOnlyHint]))))
      (is (contains? (get-in create-page [:inputSchema :properties]) "body"))
      (is (contains? (get-in create-page [:inputSchema :required]) "body")))))

(defn- mcp-request [payload token]
  (js/Request. "http://localhost/mcp"
               #js {:method "POST"
                    :headers #js {"authorization" (str "Bearer " token)
                                  "content-type" "application/json"
                                  "accept" "application/json, text/event-stream"}
                    :body (js/JSON.stringify (clj->js payload))}))

(def ^:private test-env
  #js {"DB_SYNC_STATIC_TOKEN" "tok"})

(def ^:private readonly-env
  #js {"DB_SYNC_STATIC_TOKEN" "tok" "DB_SYNC_READONLY" "1"})

(deftest mcp-initialize-test
  (async done
         (-> (p/let [resp (mcp/handle (mcp-request {:jsonrpc "2.0" :id 1 :method "initialize"
                                                    :params {:protocolVersion "2025-06-18"}}
                                                   "tok")
                                      test-env)
                     status (.-status resp)
                     body (.json resp)]
               (is (= 200 status))
               (is (= "2.0" (aget body "jsonrpc")))
               (is (= 1 (aget body "id")))
               (is (= "2025-06-18" (aget body "result" "protocolVersion")))
               (is (map? (aget body "result" "capabilities" "tools"))))
             (p/then (fn [] (done)))
             (p/catch (fn [error]
                        (is false (str error))
                        (done))))))

(deftest mcp-tools-list-test
  (async done
         (-> (p/let [resp (mcp/handle (mcp-request {:jsonrpc "2.0" :id 2 :method "tools/list"} "tok")
                                      test-env)
                     body (.json resp)
                     tools (aget body "result" "tools")]
               (is (= (count semantic-routes/operations) (count tools)))
               (is (some #(= "searchGraph" (aget % "name")) tools)))
             (p/then (fn [] (done)))
             (p/catch (fn [error]
                        (is false (str error))
                        (done))))))

(deftest mcp-unauthenticated-returns-401-test
  (async done
         (-> (p/let [resp (mcp/handle (mcp-request {:jsonrpc "2.0" :id 1 :method "tools/list"} "wrong")
                                      test-env)
                     status (.-status resp)]
               (is (= 401 status)))
             (p/then (fn [] (done)))
             (p/catch (fn [error]
                        (is false (str error))
                        (done))))))

(deftest mcp-tools-call-forwards-and-wraps-readonly-403-test
  (async done
         (-> (p/let [resp (mcp/handle (mcp-request {:jsonrpc "2.0" :id 3 :method "tools/call"
                                                     :params {:name "createPage"
                                                              :arguments {:graph-id "g1"
                                                                          :body {:title "x"}}}}
                                                    "tok")
                                      readonly-env)
                     body (.json resp)
                     result (aget body "result")]
               (is (true? (aget result "isError")))
               (is (string? (aget result "content" 0 "text")))
               (is (string/includes? (aget result "content" 0 "text") "read-only")))
             (p/then (fn [] (done)))
             (p/catch (fn [error]
                        (is false (str error))
                        (done))))))

(deftest readonly-rejects-semantic-writes-test
  (async done
         (let [request (js/Request. "http://localhost/api/v1/graphs/g1/pages"
                                    #js {:method "POST"
                                         :headers #js {"authorization" "Bearer tok"
                                                       "content-type" "application/json"}
                                         :body "{}"})]
           (-> (p/let [resp (worker-dispatch/handle-worker-fetch request readonly-env)
                       status (.-status resp)
                       body (.json resp)]
                 (is (= 403 status))
                 (is (= "server is read-only" (aget body "error"))))
               (p/then (fn [] (done)))
               (p/catch (fn [error]
                          (is false (str error))
                          (done)))))))

(deftest readonly-rejects-sync-writes-test
  (async done
         (let [request (js/Request. "http://localhost/sync/g1/tx/batch"
                                    #js {:method "POST"
                                         :headers #js {"authorization" "Bearer tok"}
                                         :body "{}"})]
           (-> (p/let [resp (worker-dispatch/handle-worker-fetch request readonly-env)
                       status (.-status resp)
                       body (.json resp)]
                 (is (= 403 status))
                 (is (= "server is read-only" (aget body "error"))))
               (p/then (fn [] (done)))
               (p/catch (fn [error]
                          (is false (str error))
                          (done)))))))

(deftest readonly-rejects-node-sync-writes-test
  (async done
    (let [request (js/Request. "http://localhost/sync/g1/snapshot/upload"
                               #js {:method "POST"
                                    :headers #js {"authorization" "Bearer tok"}
                                    :body "{}"})]
      (-> (p/let [resp (node-dispatch/handle-node-fetch {:request request
                                                          :env readonly-env
                                                          :registry (atom {})
                                                          :deps nil})
                  status (.-status resp)
                  body (.json resp)]
            (is (= 403 status))
            (is (= "server is read-only" (aget body "error"))))
          (p/then (fn [] (done)))
          (p/catch (fn [error]
                     (is false (str error))
                     (done)))))))

(deftest readonly-allows-node-sync-reads-test
  (async done
    (let [request (js/Request. "http://localhost/sync/g1/pull"
                               #js {:method "GET"
                                    :headers #js {"authorization" "Bearer tok"}})]
      (-> (p/let [resp (node-dispatch/handle-node-fetch {:request request
                                                          :env readonly-env
                                                          :registry (atom {})
                                                          :deps nil})]
            (is (not= 403 (.-status resp))))
          (p/then (fn [] (done)))
          (p/catch (fn [error]
                     (is false (str error))
                     (done)))))))

(deftest mcp-tools-call-forwards-arguments-test
  (async done
    (let [forwarded (atom nil)
          request (js/Request. "http://localhost/mcp"
                               #js {:method "POST"
                                    :headers #js {"authorization" "Bearer tok"
                                                  "content-type" "application/json"}
                                    :body (js/JSON.stringify
                                           #js {:jsonrpc "2.0" :id 9 :method "tools/call"
                                                :params #js {:name "createPage"
                                                             :arguments #js {"graph-id" "g1"
                                                                             "body" #js {"title" "x"}}}})})]
      (let [orig @worker-dispatch/handle-worker-fetch
            restore! (fn [] (set! worker-dispatch/handle-worker-fetch orig))]
        (set! worker-dispatch/handle-worker-fetch
              (fn [req _env]
                (reset! forwarded {:url (.-url req)
                                   :method (.-method req)
                                   :authorization (.get (.-headers req) "authorization")
                                   :content-type (.get (.-headers req) "content-type")
                                   :body (.text req)})
                (js/Response. "{}" #js {:status 200})))
        (-> (p/let [resp (mcp/handle request #js {"DB_SYNC_STATIC_TOKEN" "tok"})
                    body (.json resp)
                    body-text (:body @forwarded)
                    _ (is (= 200 (.-status resp)))
                    _ (is (false? (aget body "result" "isError")))
                    f (assoc @forwarded :parsed (js/JSON.parse body-text))]
              (is (= "POST" (:method f)))
              (is (= "Bearer tok" (:authorization f)))
              (is (= "application/json" (:content-type f)))
              (is (string/includes? (:url f) "/api/v1/graphs/g1/pages"))
              (is (= "x" (aget (:parsed f) "title"))))
            (p/then (fn []
                      (restore!)
                      (done)))
            (p/catch (fn [error]
                       (restore!)
                       (is false (str error))
                       (done))))))))

