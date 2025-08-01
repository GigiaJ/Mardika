(ns browser
  (:require [re-frame.core :as rf]
            [reagent.core :as r]
            [reagent.dom :as dom]
            [ajax.core :refer [GET raw-response-format json-response-format]]
            [cljs.core.async :refer [chan timeout put! go <! go-loop]] 
            [env :refer [env]]))


(defn temp-url [url callback]
  (let [c (chan)]
   (println "Fetching from URL:" url)
   (GET url
     {:response-format (raw-response-format)
      :handler #(do
                  (println "Received response:" (js->clj %))
                  (put! c %))
      :error-handler #(do
                        (js/console.error "Error occurred:" %)
                        (put! c nil))})
   (go-loop []
     (let [response (<! c)] 
       (println "processing " response)
       (if (nil? response)
         (do
           (println "Response is nil, retrying in 1 second...")
           (<! (timeout 1000)) ; Wait for 1 second before retrying
           (recur)) ; Retry 
         )
       (callback response)))))

(defn get-url [url]
  (let [c (chan)]
    (println "Fetching from URL:" url)
    (GET url
      {:response-format (json-response-format {:keywords? true})
       :handler #(do
                   (println "Received response:" (js->clj %))
                   (put! c %))
       :error-handler #(do
                         (js/console.error "Error occurred:" %)
                         (put! c nil))})
    (go-loop []
      (let [response (<! c)]
        (println "Processing: " (:results response))
        (if (nil? response)
          (do
            (println "Response is nil, retrying in 1 second...")
            (<! (timeout 1000)) ; Wait for 1 second before retrying
            (recur)) ; Retry
          (let [results  (:results response)]
            (println "Dispatching results:" results)
            (rf/dispatch [:update-items results])))))))

(defn fetch-movies [page]
  (let [url (str (env :base-url) "/trending/all/day?language=en-US&api_key=" (env :api-key) "&page=" page)]
    (println "Fetching content for page" page "with URL:" url)
    (get-url url)))

(rf/reg-event-db
 :initialize
 (fn [_ _]
   (println "Initializing state")
   (let [initial-state {:items [] :loading? true :page 1}]
     (fetch-movies 1)
     initial-state)))

(rf/reg-event-db
 :update-items
 (fn [db [_ new-items]]
   (println "Updating items with:" new-items)
   (if (seq new-items)
     (-> db
         (update :items concat new-items)
         (update :page inc)
         (assoc :loading? false))
     (assoc db :loading? false))))

(rf/reg-event-db
 :fetch-more-items
 (fn [db _]
   (let [page (:page db)]
     (println "Dispatching fetch-more-items for page" page)
     (fetch-movies page)
     (assoc db :loading? true))))

;; Trigger fetch-more-items event on scroll
(rf/reg-event-fx
 :scroll-handler
 (fn [{:keys [db]} _]
   (println "Scroll handler triggered")
   (when (<= (- (.-scrollHeight (.-documentElement js/document))
                (.-scrollTop (.-documentElement js/document))
                (.-clientHeight (.-documentElement js/document))) 200) ; Increased threshold
     (println "Almost at the bottom of the page")
     (when-not (:loading? db)
       {:db (assoc db :loading? true)
        :dispatch [:fetch-more-items]}))))

;; Add scroll event listener
(defn handle-scroll []
  (println "Scroll event detected")
  (rf/dispatch [:scroll-handler]))

(rf/reg-event-db
 :search-movies
 (fn [db [_ query]]
   (let [url (str (env :base-url)  "/search/multi?api_key=" (env :api-key) "&query=" query)]
     (println "Searching for query:" query "with URL:" url)
     (get-url url)
     (when-not (:loading? db)
       {:db (assoc db :loading? true)
        :dispatch [:fetch-more-items]}))))

(defn search-bar []
  (let [query (r/atom "")]
    (fn []
      [:div {:style {:display "flex"
                     :align-items "center"
                     :justify-content "center"
                     :margin-top "50px"}}
       [:input {:type "text"
                :class "search-input wide"
                :placeholder "Search for title..."
                :value @query
                :on-change #(reset! query (-> % .-target .-value))
                :on-key-down #(when (= (.-key %) "Enter")
                                (rf/dispatch [:search-movies @query]))}]
       [:button {:class "search-button small"
                 :on-click #(rf/dispatch [:search-movies @query])} "Search"]])))

(defn vid-src-handle [props]
  (let [tv? (= "tv" (:media_type props))]
    (str (env :vid-src) (if tv? "tv" "movie") "/" (:id props) (if tv? "/1/1" ""))))


(rf/reg-sub
 :provider
 (fn [db _]
   (:provider db)))

(rf/reg-event-db
 :load-provider
 (fn [db [_ provider]]
   (assoc db :provider provider)))

(def iframe-ref (r/atom nil))

(defn expanded-interface [props]
  (let [vidya @(rf/subscribe [:provider])]
        [:div
         {:style {:position "fixed"
                  :bottom 0
                  :left 0
                  :width "100%"
                  :height "100%"
                  :background-color "black"
                  :z-index 1000
                  :overflow "auto"}}
         [:button.close {:on-click #(rf/dispatch [:collapse-item])} "Back"] 
         [:iframe {:allowFullScreen true
                   :autoPlay true
                   :src (vid-src-handle props)
                   :ref #(reset! iframe-ref %)
                   :style {:width "100%"
                           :height "100%"
                           :z-index 1000}
                   :on-load #((go (<! (timeout 8000 ))
                                  (js/console.log (.-documentElement js/document))))}]
         #_(when vidya
           [:div {:dangerouslySetInnerHTML {:__html vidya}}])]))




#_[       [:div.vidya
        {:dangerouslySetInnerHTML {:__html (or @html-content "Loading...")}}]] ; Inject raw HTML content]
#_[:iframe {:allowFullScreen true
                 :autoPlay true
                 :src (vid-src-handle props)
                 :style {:width "100%"
                         :height "95%"
                         :z-index 1000}}]

(rf/reg-sub
 :expanded-item
 (fn [db _]
   (:expanded-item db)))

(rf/reg-event-db
 :expand-item
 (fn [db [_ item-id]]
   (assoc db :expanded-item item-id)))

(rf/reg-event-db
 :collapse-item
 (fn [db _]
   (dissoc db :expanded-item)))



;; Subscriptions
(rf/reg-sub
 :items
 (fn [db _]
   (:items db)))

(rf/reg-sub
 :loading?
 (fn [db _]
   (:loading? db)))

(defn with-hover [component]
  (fn [props]
    (let [hovered (r/atom false)
          expanded-item @(rf/subscribe [:expanded-item])]
      (fn [props]
        [:<>
         ;; Render the expanded interface if this item is expanded
         (if (= (:id props) @(rf/subscribe [:expanded-item]))
           [expanded-interface props]
           ;; Otherwise, handle hover logic and normal view
           [:div.item {:on-mouse-enter #(reset! hovered true)
                       :on-mouse-leave #(reset! hovered false)
                       :style {:background-color (if @hovered "lightblue" "lightgray")
                               :position "relative"
                               :width "100%"
                               :height "100%"}}
            ;; Show hover interface
            (if @hovered
              [:div
               {:on-click 
                            #_(fn [e] (set! (.-href js/window.location) "/api?url=https://vidbinge.dev/embed/movie/1104845"))
                            (do
                               

                               #_(temp-url "http://localhost:8080/api?url=https://vidbinge.dev/embed/movie/1104845" (fn [e] (rf/dispatch [:load-provider e])))
                               #(rf/dispatch [:expand-item (:id props)]))}
               ;; Hover overlay content
               [:div {:class "overlay"}
                ;; Title
                [:h3 (:title props)]
                ;; Description
                [:p (:overview props)]]
               [:img {:src (str "https://image.tmdb.org/t/p/w342" (:poster_path props))}]]
              ;; Default view for non-hovered state
              [component props])])]))))

(defn item-component [props]
  [:div.item
   [:img {:src (str "https://image.tmdb.org/t/p/w342" (:poster_path props))
          :id (str "cover-img" (:id props))}]])

(defn item-components [items]
  [:div.items-container
   (for [item items]
     ^{:key (:id item)}
     [(with-hover item-component) item])])

(defn chunk-items [items chunk-size]
  (let [filtered-items (filter :poster_path items)]
    (map-indexed (fn [index chunk]
                   {:id index :items chunk})
                 (partition-all chunk-size filtered-items))))


;; Components
(defn main-page []
  (r/create-class
   {:component-did-mount
    (fn []
      (println "Component did mount")
      (js/addEventListener "scroll" handle-scroll))
    :component-will-unmount
    (fn []
      (println "Component will unmount")
      (js/removeEventListener "scroll" handle-scroll))
    :reagent-render
    (fn []
      (let [items @(rf/subscribe [:items])
            loading? @(rf/subscribe [:loading?])]
        (println "Rendering items:" items)
        [:div
         [:h1 {:style {:text-align "center"}} "Mardika"]
         [:div [search-bar]]
         (for [chunk (chunk-items items 4)] ;; Chunk items into groups of 4
           ^{:key (:id chunk)} ;; Use the unique :id for the key
           [item-components (:items chunk)])
         (when loading?
           [:div "Loading..."])]))}))

(defn init []
  (rf/dispatch-sync [:initialize])
  (dom/render [main-page] (.getElementById js/document "app")))

;; Initialize app
(init)
