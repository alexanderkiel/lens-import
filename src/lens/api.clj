(ns lens.api
  (:use plumbing.core)
  (:require [clojure.core.async :as async :refer [go <!]]
            [clojure.java.io :as io]
            [clojure.tools.logging :as log]
            [cognitect.transit :as transit]
            [schema.core :as s]
            [org.httpkit.client :as http])
  (:import [java.net URI]
           [java.io ByteArrayOutputStream]))

(defn- read-transit [is]
  (transit/read (transit/reader is :json)))

(defn- write-transit [o]
  (let [out (ByteArrayOutputStream.)]
    (transit/write (transit/writer out :json) o)
    (io/input-stream (.toByteArray out))))

(defn- transit-write-str [o]
  (let [out (ByteArrayOutputStream.)]
    (transit/write (transit/writer out :json) o)
    (String. (.toByteArray out))))

(defn- resolve-uri [^URI base-uri uri]
  (.resolve base-uri (str uri)))

(defn- resolve-uri-in-form
  "Resolves relative URIs in :href values of form using base-uri."
  [base-uri form]
  (cond
    (:href form) (update-in form [:href] #(resolve-uri base-uri %))
    :else form))

(defn- resolve-uris
  "Resolves relative URIs in all :href values of doc using
  base-uri."
  [base-uri doc]
  (clojure.walk/postwalk #(resolve-uri-in-form base-uri %) doc))

(defn- parse-response [request-uri resp]
  {:pre [(instance? URI request-uri)]}
  (if (= "application/transit+json" (-> resp :headers :content-type))
    (update-in resp [:body] #(->> (read-transit %)
                                  (resolve-uris request-uri)))
    resp))

(defn fetch
  "Fetches the uri.

  Returns a channel conveying the response. Bodies of transit responses are
  parsed already and have resolved URIs."
  [uri]
  {:pre [uri]}
  (let [ch (async/chan)]
    (http/request
      {:method :get
       :url (str uri)
       :headers {"Accept" "application/transit+json"}
       :as :stream}
      (fn [{:keys [opts error] :as resp}]
        (if error
          (log/error "Error while fetching" (:url opts))
          (async/put! ch (parse-response uri resp)))
        (async/close! ch)))
    ch))

(defn execute-query
  "Executes a query on uri with params.

  Returns a channel conveying the response. Bodies of transit responses are
  parsed already and have resolved URIs."
  [uri params]
  {:pre [uri (map? params)]}
  (let [ch (async/chan)]
    (http/request
      {:method :get
       :url (str uri)
       :headers {"Accept" "application/transit+json"}
       :query-params (map-vals transit-write-str params)
       :as :stream}
      (fn [{:keys [opts error] :as resp}]
        (if error
          (log/error "Error while executing the query" (:url opts)
                     (:query-params opts))
          (async/put! ch (parse-response uri resp)))
        (async/close! ch)))
    ch))

(defn post
  "Returns a channel conveying the response."
  [uri params]
  (let [ch (async/chan)]
    (http/request
      {:method :post
       :url (str uri)
       :headers {"Accept" "application/transit+json"
                 "Content-Type" "application/transit+json"}
       :body (write-transit params)}
      (fn [{:keys [opts error] :as resp}]
        (if error
          (log/error "Error while posting" params "to" (:url opts))
          (async/put! ch resp))
        (async/close! ch)))
    ch))

(defn extract-body-if-ok [resp]
  (condp = (:status resp)
    200
    (:body resp)
    (log/error "Got non-ok response with status:" (:status resp))))

(defn update-resource [uri etag data]
  (let [ch (async/chan)]
    (http/request
      {:method :put
       :url (str uri)
       :headers {"If-Match" etag
                 "Accept" "application/transit+json"
                 "Content-Type" "application/transit+json"}
       :body (write-transit data)}
      (fn [{:keys [opts error] :as resp}]
        (if error
          (log/error "Error while updating" (:url opts))
          (async/put! ch resp))
        (async/close! ch)))
    ch))

(defn- etag [resp]
  (-> resp :headers :etag))

(defn- location [base-uri resp]
  (->> resp :headers :location (resolve-uri base-uri)))

(defn- self [doc]
  (-> doc :links :self :href))

(defn- action-href [doc id]
  (or (-> doc :actions id :href)
      (log/error "Can't find action" id "in" (keys (:actions doc)))))

(defn create!
  "Returns a channel conveying the location on success."
  [create-uri data]
  (go
    (let [resp (<! (post create-uri data))]
      (condp = (:status resp)
        201
        (log/spyf "Created %s" (location create-uri resp))
        (log/error "Failed to create" (:id data) "status:" (:status resp)
                   "body:" (:body resp))))))

(defn update-resp!
  "Returns a channel conveying the updated data."
  [resp changes]
  (let [original (:body resp) edited (merge original changes)]
    (when (not= original edited)
      (let [uri (self original)]
        (go
          (let [resp (<! (update-resource uri (etag resp) edited))]
            (condp = (:status resp)
              204
              (do (log/debug "Updated" (str uri)) edited)
              (log/error "Failed to update" (str uri) "status:" (:status resp)
                         "body:" (:body resp)))))))
    (go edited)))

(defn update!
  "Updates the resource at uri with stuff in m."
  [uri m]
  (let [resp (async/<!! (fetch uri))]
    (condp = (:status resp)
      200
      (update-resp! resp m)
      404
      (log/error "Failed to update" (str uri) "status:" (:status resp)))))

(defn upsert! [find-uri create-uri {:keys [id] :as m}]
  {:pre [find-uri]}
  (go
    (let [resp (<! (execute-query find-uri {:id id}))]
      (condp = (:status resp)
        200
        (<! (update-resp! resp m))
        404
        (some-> (create! create-uri m)
                (<!)
                (fetch)
                (<!)
                (extract-body-if-ok))
        (log/error "Failed to find" id "status:" (:status resp))))))

;; ---- Study -----------------------------------------------------------------

(defn upsert-study! [service-document study-data]
  {:pre [service-document]}
  (upsert! (action-href service-document :lens/find-study)
           (action-href service-document :lens/create-study)
           study-data))

;; ---- Study Event Def --------------------------------------------------------------

(defn upsert-study-event-def! [study m]
  {:pre [study]}
  (upsert! (action-href study :lens/find-study-event-def)
           (action-href study :lens/create-study-event-def)
           m))

;; ---- Form Def --------------------------------------------------------------

(defn upsert-form-def! [study m]
  {:pre [study]}
  (upsert! (action-href study :lens/find-form-def)
           (action-href study :lens/create-form-def)
           m))

;; ---- Item Group Def --------------------------------------------------------

(defn upsert-item-group-def! [study m]
  {:pre [study]}
  (upsert! (action-href study :lens/find-item-group-def)
           (action-href study :lens/create-item-group-def)
           m))

;; ---- Item Def --------------------------------------------------------

(defn upsert-item-def! [study m]
  {:pre [study]}
  (upsert! (action-href study :lens/find-item-def)
           (action-href study :lens/create-item-def)
           m))
