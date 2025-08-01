(ns server
  (:require [ring.middleware.resource :refer [wrap-resource]]
            [clojure.core.async :refer [go]] [clojure.java.io :as io]
            [ring.middleware.cors :refer [wrap-cors]] [clojure.string :as str]
            [ring.middleware.defaults :refer [wrap-defaults site-defaults]]
            [compojure.core :refer [routes defroutes GET POST]] [clj-http.client :as client]
            [org.httpkit.server :as server])
  (:import [java.io FileOutputStream]))

(defn download-file [url destination]
  (println "Downloading from:" url)
  (let [content (slurp url)
        response (client/get url)]
    ;; Ensure parent directories exist
    (io/make-parents destination)
    ;; Save the content to the destination file
    (spit destination content)
    {:status 200
     :headers {"Content-Type" (get-in response [:headers "content-type"] "text/plain")}
     :body (:body response)}))



(defn proxy-url [url] 
      (if url
      (try
        (let [response (client/get url)]
          {:status 200
           :headers {"Content-Type" (get-in response [:headers "content-type"] "text/plain")
                                          "Access-Control-Allow-Origin" "*"
                     "User-Agent" "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:93.0) Gecko/20100101 Firefox/93.0"
                     "Access-Control-Expose-Headers" "*"
                     "X-Final-Destination" url
                     "X-Set-Cookie" (get-in response [:headers "set-cookie"] "")
                     "Vary" "Origin"
                     "Redirect" "follow"}
           :body (:body response)})
        (catch Exception e
          {:status 500
           :headers {"Content-Type" "application/json"}
           :body (str "{\"error\": \"" (.getMessage e) "\"}")}))
      {:status 400
       :headers {"Content-Type" "application/json"}
       :body "{\"error\": \"Missing 'url' query parameter\"}"}))


(defroutes app-routes
  (GET "/" [] (slurp (clojure.java.io/resource "index.html")))
  (GET "/api" {query-params :query-params}
    (if (not= nil query-params)
      (do
        (println "Has /api so we will use the query param \n\n")
         (let [url (get query-params "url")]
           (proxy-url url)))
        )
      )
  #_(GET "/player" [] {:status 200
                     :headers {"Content-Type" (get-in response [:headers "content-type"] "text/plain")}
                     :body (:body response)})
  (POST "/api/data" [data] {:status 200 :headers {"Content-Type" "application/json"} :body (str "{\"received\": \"" data "\"}")})
  (GET "/*" {uri :uri}
    (if (not (str/starts-with? uri "/api")) 
      (do
        (println "Not /api so we are going to use the URI \n\n")
        (println uri)
        (download-file (str "https://player.vidbinge.com" uri) (str "resources" uri)))) 
    )
  #_(GET "*" [] {:status 404 :headers {"Content-Type" "text/plain"} :body "Not Found"}))

(def app
  (-> (routes app-routes)
      (wrap-resource "")
      (wrap-defaults site-defaults)
      (wrap-cors :access-control-allow-origin [#".*"]
                 :access-control-allow-methods [:get :post :put :delete :options]
                 :access-control-allow-headers ["Content-Type"])))


(defn -main []
  (println "Server running at http://localhost:3030")
  (server/run-server app {:port 3030}))


