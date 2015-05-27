(ns lens.api
  (:use plumbing.core)
  (:require [clojure.java.io :as io]
            [cognitect.transit :as transit]
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

(defn execute-query
  "Executes a query on uri with params.

  Bodies of 200 (OK) responses are parsed already and have resolved URIs."
  [uri params]
  (let [resp @(http/request {:method :get
                             :url uri
                             :headers {"Accept" "application/transit+json"}
                             :query-params params :as :stream})]
    (if (= 200 (:status resp))
      (update-in resp [:body] #(->> (read-transit %) (resolve-uris uri)))
      resp)))

(defn post-form [url params]
  (http/request {:method :post :url url :form-params params}))

;; ---- Study -----------------------------------------------------------------

(defnk create-study!
  "Creates a study with :id, :name and optional :description."
  [id name :as req]
  (let [resp @(post-form "http://localhost:5001/studies"
                         (select-keys req [:id :name :description]))]
    (if (= 201 (:status resp))
      (println "Created study" id)
      (println "Failed creating study" id " status" (:status resp)))))

(defnk update-study!
  [uri etag id name :as req]
  (assert uri)
  (let [resp
        @(http/request
           {:method :put
            :url uri
            :headers {"If-Match" etag
                      "Accept" "application/transit+json"
                      "Content-Type" "application/transit+json"}
            :body (write-transit (select-keys req [:id :name :description]))})]
    (if (= 204 (:status resp))
      (println "Updated study" id)
      (println "Failed updating study" id " status" (:status resp)))))

(defn- study-props [m]
  (select-keys m [:id :name :description]))

(defnk create-or-update-study! [id :as req]
  (let [uri "http://localhost:5001/find-study"
        resp (execute-query uri {:id id})]
    (if (= 200 (:status resp))
      (when (not= (study-props req) (study-props (:body resp)))
        (update-study! (assoc req :uri (-> resp :body :links :self :href)
                                  :etag (-> resp :headers :etag))))
      (create-study! req))))
