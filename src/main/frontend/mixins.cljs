(ns frontend.mixins
  (:require [rum.core :as rum]
            [goog.dom :as dom]
            [goog.object :as gobj]
            [frontend.keyboard :as keyboard]
            [frontend.util :refer-macros [profile] :refer [keyname]])
  (:import [goog.events EventHandler]))

(defn detach
  "Detach all event listeners."
  [state]
  (some-> state ::event-handler .removeAll))

(defn listen
  "Register an event `handler` for events of `type` on `target`."
  [state target type handler & [opts]]
  (when-let [^EventHandler event-handler (::event-handler state)]
    (.listen event-handler target (name type) handler (clj->js opts))))

(def event-handler-mixin
  "The event handler mixin."
  {:will-mount
   (fn [state]
     (assoc state ::event-handler (EventHandler.)))
   :will-unmount
   (fn [state]
     (detach state)
     (dissoc state ::event-handler))})

;; (defn timeout-mixin
;;   "The setTimeout mixin."
;;   [name t f]
;;   {:will-mount
;;    (fn [state]
;;      (assoc state name (util/set-timeout t f)))
;;    :will-unmount
;;    (fn [state]
;;      (let [timeout (get state name)]
;;        (util/clear-timeout timeout)
;;        (dissoc state name)))})

;; (defn interval-mixin
;;   "The setInterval mixin."
;;   [name t f]
;;   {:will-mount
;;    (fn [state]
;;      (assoc state name (util/set-interval t f)))
;;    :will-unmount
;;    (fn [state]
;;      (when-let [interval (get state name)]
;;        (util/clear-interval interval))
;;      (dissoc state name))})

(defn hide-when-esc-or-outside
  [state & {:keys [on-hide node visibilitychange? outside?]}]
  (try
    (let [dom-node (rum/dom-node state)]
      (when-let [dom-node (or node dom-node)]
        (or (false? outside?)
            (listen state js/window "mousedown"
                    (fn [e]
                      ;; If the click target is outside of current node
                      (when-not (dom/contains dom-node (.. e -target))
                        (on-hide state e :click)))))
        (listen state js/window "keydown"
                (fn [e]
                  (case (.-keyCode e)
                    ;; Esc
                    27 (on-hide state e :esc)
                    nil)))
        (when visibilitychange?
          (listen state js/window "visibilitychange"
                  (fn [e]
                    (on-hide state e :visibilitychange))))))
    (catch js/Error e
      ;; TODO: Unable to find node on an unmounted component.
      nil)))

(defn resize-layout
  [state ref]
  (listen state js/window "resize"
          (fn [e]
            (reset! ref [js/window.innerWidth js/window.innerHeight]))))

(defn on-enter
  [state & {:keys [on-enter node]}]
  (let [node (or node (rum/dom-node state))]
    (listen state node "keyup"
            (fn [e]
              (case (.-keyCode e)
                ;; Enter
                13 (on-enter e)
                nil)))))

(defn on-key-up
  [state keycode-map all-handler]
  (let [node (rum/dom-node state)]
    (listen state js/window "keyup"
            (fn [e]
              (let [key-code (.-keyCode e)]
                (when-let [f (get keycode-map key-code)]
                  (f state e))
                (when all-handler (all-handler e key-code)))))))

(defn on-key-down
  ([state keycode-map]
   (on-key-down state keycode-map {}))
  ([state keycode-map {:keys [not-matched-handler all-handler]}]
   (let [node (rum/dom-node state)]
     (listen state js/window "keydown"
             (fn [e]
               (let [key-code (.-keyCode e)]
                 (if-let [f (get keycode-map key-code)]
                   (f state e)
                   (when (and not-matched-handler (fn? not-matched-handler))
                     (not-matched-handler e key-code)))
                 (when (and all-handler (fn? all-handler))
                   (all-handler e key-code))))))))

(defn event-mixin
  ([attach-listeners]
   (event-mixin attach-listeners identity))
  ([attach-listeners init-callback]
   (merge
    event-handler-mixin
    {:init (fn [state props]
             (init-callback state))
     :did-mount (fn [state]
                  (attach-listeners state)
                  state)
     :did-remount (fn [old-state new-state]
                    (detach old-state)
                    (attach-listeners new-state)
                    new-state)})))

(defn modal
  [k]
  (event-mixin
   (fn [state]
     (let [open? (get state k)]
       (hide-when-esc-or-outside
        state
        :on-hide (fn []
                   (when (and open? @open?)
                     (reset! open? false))))))
   (fn [state]
     (let [open? (atom false)
           component (:rum/react-component state)]
       (add-watch open? ::open
                  (fn [_ _ _ _]
                    (rum/request-render component)))
       (assoc state
              :open? open?
              :close-fn (fn []
                          (reset! open? false))
              :open-fn (fn []
                         (reset! open? true))
              :toggle-fn (fn []
                           (swap! open? not)))))))

(defn will-mount-effect
  [handler]
  {:will-mount (fn [state]
                 (handler (:rum/args state))
                 state)})

(defn shortcuts
  [listener dispatcher]
  {:did-mount
   (fn [state]
     (->> dispatcher
          (reduce-kv (fn [result id handle-fn]
                       (assoc result id (partial handle-fn state)))
                     {})
          keyboard/install-shortcuts!
          (assoc state listener)))
   :did-remount (fn [old-state new-state]

                  ;; remove shortcuts and unlisten
                  (when-let [f (get old-state listener)]
                    (f))

                  ;; update new states
                  (->> dispatcher
                       (reduce-kv (fn [result id handle-fn]
                                    (assoc result id (partial handle-fn new-state)))
                                  {})
                       keyboard/install-shortcuts!
                       (assoc new-state listener)))
   :will-unmount
   (fn [state]
     (when-let [f (get state listener)]
       (f))
     (dissoc state listener))})

(defn perf-measure-mixin
  [desc]
  "Does performance measurements in development."
  {:wrap-render
   (fn wrap-render [render-fn]
     (fn [state]
       (profile
        (str "Render " desc)
        (render-fn state))))})
