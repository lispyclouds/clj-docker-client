;   This file is part of clj-docker-client.
;
;   clj-docker-client is free software: you can redistribute it and/or modify
;   it under the terms of the GNU Lesser General Public License as published by
;   the Free Software Foundation, either version 3 of the License, or
;   (at your option) any later version.
;
;   clj-docker-client is distributed in the hope that it will be useful,
;   but WITHOUT ANY WARRANTY; without even the implied warranty of
;   MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
;   GNU Lesser General Public License for more details.
;
;   You should have received a copy of the GNU Lesser General Public License
;   along with clj-docker-client. If not, see <http://www.gnu.org/licenses/>.

(ns clj-docker-client.core
  (:require [clojure.string :as s]
            [jsonista.core :as json]
            [pem-reader.core :as pem]
            [clj-docker-client.requests :as req]
            [clj-docker-client.specs :as spec])
  (:import [okhttp3.tls
            HandshakeCertificates$Builder
            HeldCertificate]
           [okhttp3 OkHttpClient$Builder]
           [java.security KeyPair]
           [java.security.cert X509Certificate]))

(defn- read-cert
  [path]
  (-> path
      pem/read
      :certificate))

(defn- try-json-parse
  [value]
  (try
    (json/read-value value json/keyword-keys-object-mapper)
    (catch Exception _ value)))

(defn- make-builder-fn
  [{:keys [ca cert key]}]
  (let [{:keys [public-key private-key]} (pem/read key)
        key-pair                         (KeyPair. public-key private-key)
        held-cert                        (HeldCertificate. key-pair (read-cert cert))
        handshake-certs                  (-> (HandshakeCertificates$Builder.)
                                             (.addTrustedCertificate (read-cert ca))
                                             (.heldCertificate held-cert (into-array X509Certificate []))
                                             (.build))]
    (fn [^OkHttpClient$Builder builder]
      (.sslSocketFactory builder
                         (.sslSocketFactory handshake-certs)
                         (.trustManager handshake-certs)))))

(defn ^:deprecated connect
  "Deprecated but still there for compatibility reasons."
  [{:keys [uri connect-timeout read-timeout write-timeout call-timeout mtls]}]
  (when (nil? uri)
    (req/panic! ":uri is required"))
  {:uri        uri
   :builder-fn (if mtls
                 (make-builder-fn mtls)
                 identity)
   :timeouts   {:connect-timeout connect-timeout
                :read-timeout    read-timeout
                :write-timeout   write-timeout
                :call-timeout    call-timeout}})

(defn categories
  "Returns the available categories.

  Takes an optional API version for specific ones."
  ([] (categories nil))
  ([version]
   (->> (get (spec/fetch-spec version) "paths")
        (map #(second (s/split (key %) #"/")))
        (map keyword)
        (into #{}))))

(defn client
  "Constructs a client for a specified category.

  Returns the client.

  Examples are: :containers, :images, etc"
  [{:keys [category conn api-version]
    :as   args}]
  (when (some nil? [category conn])
    (req/panic! ":category, :conn are required"))
  (let [args (if-let [mtls (:mtls conn)]
               (-> args
                   (update-in [:conn] assoc :builder-fn (make-builder-fn mtls))
                   (update-in [:conn] dissoc :mtls))
               args)]
    (assoc args :paths (:paths (spec/get-paths-of-category category api-version)))))

(defn ops
  "Returns the supported ops for a client."
  [client]
  (->> client
       :paths
       (map spec/->endpoint)
       (mapcat :ops)
       (map :op)))

(defn doc
  "Returns the doc of the supplied category and operation"
  [{:keys [category api-version]} operation]
  (when (nil? category)
    (req/panic! ":category is required"))
  (update-in (select-keys (spec/request-info-of category operation api-version) [:doc :params])
             [:params]
             #(map (fn [param]
                     (select-keys param [:name :type :description]))
                   %)))

(defn invoke
  "Performs the operation with the specified client and a map of options.

  Returns the resulting map.

  Options are:
  :op specifying the operation to invoke. Required.
  :params specifying the params to be passed to the :op.
  :as specifying the result. Can be either of :stream, :socket, :data. Defaults to :data.
  :throw-exception? Throws a RuntimeException when a response has a status >= 400. Defaults to false.
                    Standard connection exceptions may still be thrown regardless.

  If a :socket is requested, the underlying UNIX socket is returned."
  [{:keys [category conn api-version]} {:keys [op params as throw-exception? throw-entire-message?]}]
  (when (some nil? [category conn op])
    (req/panic! ":category, :conn are required in client, :op is required in operation map"))
  (let [request-info                     (spec/request-info-of category op api-version)
        _                                (when (nil? request-info)
                                           (req/panic! "Invalid params for invoking op."))
        {:keys [body query header path]} (->> request-info
                                              :params
                                              (reduce (partial spec/gather-request-params params) {}))
        response                         (req/fetch {:conn                  (req/connect* conn)
                                                     :url                   (:path request-info)
                                                     :method                (:method request-info)
                                                     :query                 query
                                                     :header                header
                                                     :body                  (-> body
                                                                                vals
                                                                                first)
                                                     :path                  path
                                                     :as                    as
                                                     :throw-exception?      throw-exception?
                                                     :throw-entire-message? throw-entire-message?})]
    (case as
      (:socket :stream) response
      (try-json-parse response))))

(comment
  (require '[clojure.java.io :as io])
  (.getPath (java.net.URI. "unix:///var/run/docker.sock"))
  (req/connect* {:uri "unix:///var/run/docker.sock"})
  (req/fetch {:conn (req/connect* {:uri "unix:///var/run/docker.sock"})
              :url  "/v1.41/_ping"})
  (req/fetch {:conn   (req/connect* {:uri "unix:///var/run/docker.sock"})
              :url    "/containers/create"
              :method :post
              :query  {:name "conny"}
              :body   {:Image "busybox:musl"
                       :Cmd   "ls"}
              :header {:X-Header "header"}})
  (req/fetch {:conn   (req/connect* {:uri "unix:///var/run/docker.sock"})
              :url    "/containers/cp-this/archive"
              :method :put
              :query  {:path "/root/src"}
              :body   (-> "src.tar.gz"
                          io/file
                          io/input-stream)})
  ;; PLANNED API
  (def http-tls-ping
    (client {:category    :_ping
             :conn        {:uri  "https://localhost:8000"
                           :mtls {:ca   "ca.pem"
                                  :key  "key.pem"
                                  :cert "cert.pem"}}
             :api-version "v1.41"}))
  (invoke http-tls-ping {:op :SystemPing})

  (def ping
    (client {:category :_ping
             :conn     {:uri "unix:///var/run/docker.sock"}}))
  (invoke ping {:op :SystemPing})
  (categories)
  (categories "v1.41")
  (def containers
    (client {:category    :containers
             :conn        {:uri "unix:///var/run/docker.sock"}
             :api-version "v1.41"}))
  (def images
    (client {:category :images
             :conn     {:uri "unix:///var/run/docker.sock"}}))
  (invoke {:category :_ping
           :conn     {:uri "unix:///var/run/docker.sock"}}
          {:op :SystemPing})
  (ops images)
  (ops containers)
  (doc containers :ContainerAttach)
  (def sock
    (invoke containers
            {:op     :ContainerAttach
             :params {:id     "conny"
                      :stream true
                      :stdin  true}
             :as     :socket}))
  (io/copy "hello ohai" (.getOutputStream sock))
  (.close sock)
  (invoke containers
          {:op     :ContainerList
           :params {:all true}})
  (invoke containers
          {:op     :ContainerCreate
           :params {:name "conny"
                    :body {:Image "busybox:musl"
                           :Cmd   "ls"}}})
  (invoke containers
          {:op     :PutContainerArchive
           :params {:path        "/root"
                    :id          "cp-this"
                    :inputStream (-> "src.tar.gz"
                                     io/file
                                     io/input-stream)}})
  (try
    (invoke containers
            {:op                    :ContainerArchive
             :params                {:id   "conny"
                                     :path "/yes"}
             :as                    :stream
             :throw-exception?      true
             :throw-entire-message? true})
    (catch Exception e
      (-> e
          ex-data
          :body
          slurp)))

  (doc containers :ContainerCreate)
  (invoke images {:op :ImageList})
  (def pinger
    (client {:category    :_ping
             :conn        {:uri "unix:///var/run/docker.sock"}
             :api-version "v1.25"}))
  (invoke pinger {:op :SystemPing})
  (invoke containers
          {:op     :ContainerLogs
           :params {:id     "conny"
                    :stdout true
                    :follow true}
           :as     :stream}))
