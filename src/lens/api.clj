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

(defn- resolve-uri [base-uri uri]
  (str (.resolve (URI/create base-uri) uri)))

(defn- resolve-uri-in-form
  "Resolves relative URIs in :href and :action values of form using base-uri."
  [base-uri form]
  (cond
    (:href form) (update-in form [:href] #(resolve-uri base-uri %))
    (:action form) (update-in form [:action] #(resolve-uri base-uri %))
    :else form))

(defn- resolve-uris
  "Resolves relative URIs in all :href and :action values of doc using
  base-uri."
  [base-uri doc]
  (clojure.walk/postwalk #(resolve-uri-in-form base-uri %) doc))

(defn- parse-response [request-uri resp]
  (if (= "application/transit+json" (-> resp :headers :content-type))
    (update-in resp [:body] #(->> (read-transit %) (resolve-uris request-uri)))
    resp))

(defn fetch
  "Fetches the uri with.

  Bodies of transit responses are parsed already and have resolved URIs."
  [uri]
  (log/debug "Fetch" uri)
  (let [resp @(http/request {:method :get
                             :url uri
                             :headers {"Accept" "application/transit+json"}
                             :as :stream})]
    (parse-response uri resp)))

(defn execute-query
  "Executes a query on uri with params.

  Bodies of transit responses are parsed already and have resolved URIs."
  [uri params]
  {:pre [uri (map? params)]}
  (let [resp @(http/request {:method :get
                             :url uri
                             :headers {"Accept" "application/transit+json"}
                             :query-params params :as :stream})]
    (parse-response uri resp)))

(defn post-form [url params]
  (http/request {:method :post :url url :form-params params}))

(defn extract-body-if-ok [resp]
  (condp = (:status resp)
    200
    (:body resp)
    (log/error "Got non-ok response with status:" (:status resp))))

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
  (let [resp
        @(http/request
           {:method :put
            :url uri
            :headers {"If-Match" etag
                      "Accept" "application/transit+json"
                      "Content-Type" "application/transit+json"}
            :body (write-transit (select-keys req [:id :name :description]))})]
    (condp = (:status resp)
      204
      (log/debug "Updated study" id)
      (log/error "Failed to update study" id "status:" (:status resp)))))

(defnk create-or-update-study! [id :as req]
  (let [uri "http://localhost:5001/find-study"
        resp (execute-query uri {:id id})]
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

;; ---- Form Def --------------------------------------------------------------

(defn- form-def-props [m]
  (select-keys m [:id :name :description]))

(defnk create-form-def! [study id :as req]
  (let [uri (-> study :forms :lens/create-form-def :action)
        resp @(post-form uri (form-def-props req))]
    (condp = (:status resp)
      201
      (log/spyf "Created form def at %s" (->> resp :headers :location
                                              (resolve-uri uri)))
      (log/error "Failed to create form def" id "status:" (:status resp)))))

(defnk update-form-def!
  "Updates a form def.

  URI and ETag are from a GET request before."
  [uri etag :- s/Str id :as req]
  (let [resp
        @(http/request
           {:method :put
            :url uri
            :headers {"If-Match" etag
                      "Accept" "application/transit+json"
                      "Content-Type" "application/transit+json"}
            :body (write-transit (select-keys req [:id :name :description]))})]
    (condp = (:status resp)
      204
      (log/debug "Updated form def" id)
      (log/error "Failed to update form def" id "status:" (:status resp)))))

(defnk create-or-update-form-def! [study id :as req]
  (let [uri (-> study :forms :lens/find-form-def :action)
        resp (execute-query uri {:id id})]
    (condp = (:status resp)
      200
      (when (not= (form-def-props req) (form-def-props (:body resp)))
        (update-form-def! (assoc req :uri (-> resp :body :links :self :href)
                                     :etag (-> resp :headers :etag))))
      404
      (some-> (create-form-def! req)
              (fetch)
              (extract-body-if-ok))
      (log/error "Failed to find form def" id "status:" (:status resp)))))
