(ns socket-json-rpc.server
  (:require [clojure.string :as str]
            [clojure.data.json :as json]
            [clojure.core.async :as async]
            [com.gearswithingears.async-sockets :refer :all]))

(defmacro def-
  "Define a new private variable in 'defn-' style."
  [sym init]
  `(def ~(with-meta sym {:private true}) ~init))

(def- jrpc-namespace {})

(def- parse-error '(-32700 "Parse error"))
(def- invalid-request '(-32600 "Invalid request"))
(def- method-not-found '(-32601 "Method not found"))
(def- invalid-params '(-32602 "Invalid params"))
(def- internal-error '(-32603 "Internal error"))

(defn error
  "Generates the return JSON for an error condition."
  ([err]
    (error (first err) (second err)))
  ([code message]
    (if (and (<= code -32000) (>= code -32768))
      {"jsonrpc" "2.0"
       "error" {"code" -32000
                "message" "Application attempted to use reserved error code"}}
      {"jsonrpc" "2.0"
       "error" {"code" code
                "message" message}})))

(defn- server-error
  "Generates the return JSON for an internal server error."
  ([err]
    (server-error (first err) (second err)))
  ([code message]
    {"jsonrpc" "2.0"
     "error" {"code" code
              "message" message}}))

(defn respond
  "Generates the return JSON for a function's return value."
  [result]
  {"jsonrpc" "2.0"
   "result" result})

(defmacro when-valid
  "Continues with body if the RPC call is valid. Otherwise, returns an appropriate
  error. body automatically receives the variables 'params', 'id' and 'method'"
  [call body]
  `(let [params# (get ~call "params")
         id# (get ~call "id")]
     (if (= (get ~call "jsonrpc") "2.0")
       (if-let [method# (get (var-get #'jrpc-namespace) (get ~call "method"))]
         (if (or (vector? params#) (map? params#) (= params# nil))
           ((fn [~'params ~'id ~'method] ~body) params# id# method#)
           (assoc (#'server-error (var-get #'invalid-request)) "id" id#))
         (assoc (#'server-error (var-get #'method-not-found)) "id" id#))
       (assoc (#'server-error (var-get #'invalid-request)) "id" id#))))

; FIXME notification argument currently hardcoded to false
(defn- execute-single
  "Takes the JSON input of a single procedure call, and returns a map containing
  the return JSON, if applicable (i.e. notifications do not return anything).
  Handles adding ids to return values automatically."
  [call]
  (when-valid call
    (if (map? params)
      (loop [args (into [] (map (constantly nil) (first method)))
             params params]
        (if (vector? args)
          args ; Don't argue - this returns an invalid params error
          (if-let [arg (first params)]
            (recur (try (assoc args (.indexOf (first params)) (second params))
                        (catch Exception _
                          (assoc (server-error invalid-params) "id" id)))
                   (rest params))
            (assoc ((second method) args false) "id" id))))
      (if (= (count (first method)) (count params))
        (assoc ((second method) params false) "id" id)
        (assoc (server-error invalid-params) "id" id)))))

(defn- execute
  "Handles request strings, dealing with batches etc. along the way."
  [request]
  (let [input (json/read-str request)]
    (def wasd input) ; XXX Debug
    (if (vector? input)
      (if (empty? input)
        (assoc (server-error invalid-request) "id" nil)
        (loop [input input return []]
          (if-let [current (first input)]
            (recur (rest input)
                   (conj return (execute-single current)))
            (json/write-str (vec (remove nil? return))))))
      (json/write-str (execute-single input)))))

; From http://stackoverflow.com/a/12503724
(defn- parse-int
  "Parses the first continuous number only"
  [s]
  (new Integer (re-find #"\d+" s)))

(defn- socket-io
  "Reads all incoming data from the socket, then sends the completed string to
  execute, before writing the return data to the socket."
  [socket]
  (async/go-loop [length 0 request ""]
    (when-let [line (async/<! (:in socket))]
      (if (zero? length)
        (recur (parse-int line) request)
        (let [request (str request line)]
          (if (< (count request) length)
            (recur length (str request "\n"))
            (async/>! (:out socket) (execute request))))))))

(defmacro defprocedure
  "Creates a new procedure that can be called through the JSON-RPC interface.
  The supplied function receives two arguments automatically - 'args', which is
  a vector of unnamed arguments (i.e. the caller supplied an Array), and
  'notification', which is true when the function should not return anything.
  The named-args argument takes a vector of named arguments, and is used if the
  caller supplied a parameters Object. The names must be specified in the same
  order as they should appear in the unnamed arguments version."
  [sym named-args func]
  `(if-not (str/starts-with? (name (quote ~sym)) "rpc.")
     (alter-var-root #'jrpc-namespace
                     (constantly (assoc (var-get #'jrpc-namespace)
                                        (name (quote ~sym))
                                        (list ~named-args
                                              (fn [~'args ~'notification] ~func)))))
     (throw (new IllegalArgumentException
                     "rpc.* method names are reserved for system extensions"))))

(defn start-async
  "Creates a new asynchronous socket server with a given port, optional backlog
  (maximum queue length of incoming connection indications, 50 by default) and
  optional bind address (localhost by default), and returns immediately with a
  communications channel that can be used to command the server to shut down."
  ([port]
    (start-async port default-server-backlog nil))
  ([port backlog]
    (start-async port backlog nil))
  ([port backlog bind-addr]
    (let [server (socket-server port backlog bind-addr)]
      (.addShutdownHook (Runtime/getRuntime)
                        (new Thread (fn [] (stop-socket-server server))))
      (async/go-loop []
        (when-let [connection (async/<! (:connections server))]
          (socket-io connection)
          (recur))))))

(defn start
  "Creates a new asynchronous socket server with a given port, optional backlog
  (maximum queue length of incoming connection indications, 50 by default) and
  optional bind address (localhost by default), blocking the calling thread in
  the process. Use this server instead of the async server to prevent the main
  thread from exiting."
  ([port]
    (start port default-server-backlog nil))
  ([port backlog]
    (start port backlog nil))
  ([port backlog bind-addr]
    (let [channel (start-async port backlog bind-addr)]
      (loop []
        (when (async/<!! channel)
          (recur))))))