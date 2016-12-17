# Compost

Library that manages lifecycle of stateful components. 
This is a variation of https://github.com/stuartsierra/component project's idea.
At the moment this library does not support ClojureScript.

## Status

Beta, you are welcome to try it. 

## Usage

Add this dependency to your project
```
  [net.readmarks/compost "0.1.0"]
```

See tests for examples. Component declaration has form
```clojure
   {:requires #{:required-component-id-1 :required-component-id-2}}
    :this initial-state
    :get (fn [this] ...) ;; Returns value of this component that other components will get as dependency. 
    :start (fn [this dependency-components-map] ...) ;; Acquire resources (open connections, start threads ...)
    :stop (fn [this] ...)} ;; Release resources.
```
All fields are optional, defaults are:
```clojure
   {:requires #{}
    :this nil
    :get identity 
    :start (fn [this dependency-components-map] this)
    :stop identity}
```
`:start` and `:stop` functions should return new value of component's `:this`.
If component acquires resources in `:start` it must release them in `:stop`. 
If `:stop` function is present then it should handle gracefully `(stop (stop component))` case.
System declaration is a plain map
```clojure
  {:component-1-id component-1-declaration
   :component-2-id component-2-declaration
   ...
  }
```

Lifecycle usage example
```clojure
 (let [s (compost/start system-map #{:web-component :some-worker-component})]
   (Thread/sleep 5000)
   (compost/stop s))
```

### Using com.stuartsierra.component components

You can adapt existing components as follows:

```clojure
(defn component-using [init using]
  {:requires (set using)
   :this init
   :start (fn [this deps]
            (-> (merge this deps)
                component/start)
   :stop component/stop})
 
(def system 
  {:conn-source (component-using
                   (->MyConnPool)
                   [])
   :dao (component-using
          (map->MyDbComponent {})
          [:conn-source])}
```

Note that unlike com.stuartsierra.component sequence of `n.r.compost/stop` might differ from reverse startup one.
Only explicitly declared dependencies are respected. If you need to account for implicit dependencies 
you can add additional elements to components' `:require` collections.

## Motivation

I like what com.stuartsierra.component provides but I also want
* Use any value as component. E.g. often I want a function closure to be a component. 
  Or, alternatively, a component be visible as a function. Besides this, I do not like the idea of
  always keeping dependency reference even though it might be needed only in start function. 
* Do not require a new type for each component. Implementing `Lifecycle`
  gets in the way when you only need an ad hoc component. Also requirement for component 
  to be a map and implement LifeCycle effectively restricts component to be a record. 
  This also means that sometimes people resort to work-arounds to avoid creating new types.
* Use plain Clojure data structures to configure system. I think that putting configuration into metadata
  was a mistake. Instead of streamlining it actually complicates code. System configuration is also data 
  that one might want to inspect or modify. Give it equal rights :)
* Be compatible with com.stuartsierra.component/Lifecycle components. 
  There are already lots of such components and this is a good thing. 
  This part should require only small amount of glue code.

## License 

Copyright © Petr Gladkikh <PetrGlad@gmail.com>

Distributed under the Eclipse Public License either version 1.0 or (at
your option) any later version.
