(ns lens.api
  (:use plumbing.core)
  (:require [clojure.java.io :as io]
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
  "Fetches the uri with.

  Bodies of transit responses are parsed already and have resolved URIs."
  [uri]
  (log/debug "Fetch" uri)
  (let [resp @(http/request {:method :get
                             :url (str uri)
                             :headers {"Accept" "application/transit+json"}
                             :as :stream})]
    (parse-response uri resp)))

(defn execute-query
  "Executes a query on uri with params.

  Bodies of transit responses are parsed already and have resolved URIs."
  [uri params]
  {:pre [uri (map? params)]}
  (let [resp @(http/request {:method :get
                             :url (str uri)
                             :headers {"Accept" "application/transit+json"}
                             :query-params params :as :stream})]
    (parse-response uri resp)))

(defn post-form [uri params]
  (http/request {:method :post :url (str uri) :form-params params}))

(defn extract-body-if-ok [resp]
  (condp = (:status resp)
    200
    (:body resp)
    (log/error "Got non-ok response with status:" (:status resp))))

(defn update-resource [uri etag body]
  (http/request
    {:method :put
     :url (str uri)
     :headers {"If-Match" etag
               "Accept" "application/transit+json"
               "Content-Type" "application/transit+json"}
     :body (write-transit body)}))

;; ---- Study -----------------------------------------------------------------

(defn- study-props [m]
  (select-keys m [:id :name :description]))

(defnk create-study!
  "Creates a study with :id, :name and optional :description."
  [id :as req]
  (let [uri "http://localhost:5001/studies"
        resp @(post-form uri (study-props req))]
    (condp = (:status resp)
      201
      (log/spyf "Created study at %s" (->> resp :headers :location
                                           (resolve-uri uri)))
      (log/error "Failed creating study" id "status:" (:status resp)))))

(defnk update-study!
  "Updates a study.

  URI and ETag are from a GET request before."
  [uri etag :- s/Str id :as req]
  (let [resp @(update-resource uri etag (study-props req))]
    (condp = (:status resp)
      204
      (log/debug "Updated study" id)
      (log/error "Failed to update study" id "status:" (:status resp)))))

(defn upsert-study! [find-study-uri {:keys [id] :as req}]
  (let [resp (execute-query find-study-uri {:id id})]
    (condp = (:status resp)
      200
      (let [study (:body resp)]
        (when (not= (study-props req) (study-props study))
          (update-study! (assoc req :uri (-> study :links :self :href)
                                    :etag (-> resp :headers :etag))))
        study)
      404
      (some-> (create-study! req)
              (fetch)
              (extract-body-if-ok))
      (log/error "Failed to find study" id "status:" (:status resp)))))

;; ---- form-def --------------------------------------------------------------

(defn- form-def-props [m]
  (select-keys m [:id :name :description]))

(defn create-form-def! [study {:keys [id] :as req}]
  (if-let [uri (-> study :actions :lens/create-form-def :href)]
    (let [resp @(post-form uri (form-def-props req))]
      (condp = (:status resp)
        201
        (log/spyf "Created form-def at %s" (->> resp :headers :location
                                                (resolve-uri uri)))
        (log/error "Failed to create form-def" id "status:" (:status resp))))
    (log/error "Missing :lens/create-form-def form in study.")))

(defnk update-form-def!
  "Updates a form-def.

  URI and ETag are from a GET request before."
  [uri etag :- s/Str id :as req]
  (let [resp @(update-resource uri etag (form-def-props req))]
    (condp = (:status resp)
      204
      (log/debug "Updated form-def" id)
      (log/error "Failed to update form-def" id "status:" (:status resp)))))

(defn upsert-form-def! [study {:keys [id] :as req}]
  (if-let [uri (-> study :actions :lens/find-form-def :href)]
    (let [resp (execute-query uri {:id id})]
      (condp = (:status resp)
        200
        (let [form-def (:body resp)]
          (when (not= (form-def-props req) (form-def-props form-def))
            (update-form-def! (assoc req :uri (-> form-def :links :self :href)
                                         :etag (-> resp :headers :etag))))
          form-def)
        404
        (some-> (create-form-def! study req)
                (fetch)
                (extract-body-if-ok))
        (log/error "Failed to find form-def" id "status:" (:status resp))))
    (log/error "Missing :lens/find-form-def form in study.")))

;; ---- item-group-def --------------------------------------------------------

(defn- item-group-def-props [m]
  (select-keys m [:id :name :description]))

(defn create-item-group-def! [study {:keys [id] :as req}]
  (if-let [uri (-> study :actions :lens/create-item-group-def :href)]
    (let [resp @(post-form uri (item-group-def-props req))]
      (condp = (:status resp)
        201
        (log/spyf "Created item-group-def at %s" (->> resp :headers :location
                                                      (resolve-uri uri)))
        (log/error "Failed to create item-group-def" id "status:"
                   (:status resp))))
    (log/error "Missing :lens/create-item-group-def form in study.")))

(defnk update-item-group-def!
  "Updates an item-group-def.

  URI and ETag are from a GET request before."
  [uri etag :- s/Str id :as req]
  (let [resp @(update-resource uri etag (item-group-def-props req))]
    (condp = (:status resp)
      204
      (log/debug "Updated item-group-def" id)
      (log/error "Failed to update item-group-def" id "status:"
                 (:status resp)))))

(defn upsert-item-group-def! [study {:keys [id] :as req}]
  (if-let [uri (-> study :actions :lens/find-item-group-def :href)]
    (let [resp (execute-query uri {:id id})]
      (condp = (:status resp)
        200
        (let [item-group-def (:body resp)]
          (when (not= (item-group-def-props req)
                      (item-group-def-props item-group-def))
            (update-item-group-def!
              (assoc req :uri (-> item-group-def :links :self :href)
                         :etag (-> resp :headers :etag))))
          item-group-def)
        404
        (some-> (create-item-group-def! study req)
                (fetch)
                (extract-body-if-ok))
        (log/error "Failed to find item-group def" id "status:"
                   (:status resp))))
    (log/error "Missing :lens/find-item-group-def form in study.")))
