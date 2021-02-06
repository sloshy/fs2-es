// When the user clicks on the search box, we want to toggle the search dropdown
function displayToggleSearch(e) {
  e.preventDefault();
  e.stopPropagation();

  closeDropdownSearch(e);
  
  if (idx === null) {
    console.log("Building search index...");
    prepareIdxAndDocMap();
    console.log("Search index built.");
  }
  const dropdown = document.querySelector("#search-dropdown-content");
  if (dropdown) {
    if (!dropdown.classList.contains("show")) {
      dropdown.classList.add("show");
    }
    document.addEventListener("click", closeDropdownSearch);
    document.addEventListener("keydown", searchOnKeyDown);
    document.addEventListener("keyup", searchOnKeyUp);
  }
}

//We want to prepare the index only after clicking the search bar
var idx = null
const docMap = new Map()

function prepareIdxAndDocMap() {
  const docs = [  
    {
      "title": "DeferredMap",
      "url": "/fs2-es/docs/deferredmap",
      "content": "DeferredMap A DeferredMap is a concurrent MapRef that is specifically optimized for awaiting asynchronous values that may not have completed yet. It is used internally by EventStateCache to keep track of what resources are already being awaited, so you do not duplicate requests. Here’s a brief example of how you can use it, for awaiting a specific concurrent job to finish, by-key: import cats.effect._ import cats.effect.concurrent.Deferred import dev.rpeters.fs2.es.data.DeferredMap import scala.concurrent.duration._ import scala.concurrent.ExecutionContext.global //Needed implicits for the examples here implicit val cs: ContextShift[IO] = IO.contextShift(global) implicit val timer: Timer[IO] = IO.timer(global) val example = DeferredMap[IO].empty[String, String].flatMap { dmap =&gt; //Helper function to complete a job concurrently after three seconds def completeJobAfter3s(key: String) = for { d &lt;- Deferred[IO, String] //Create our async result that has not finished yet _ &lt;- dmap.add(key)(d) //Add it to the map _ &lt;- timer.sleep(3.seconds).flatMap(_ =&gt; d.complete(\"success\")).start //Complete it asynchronously } yield () for { _ &lt;- completeJobAfter3s(\"job1\") _ &lt;- completeJobAfter3s(\"job2\") res1 &lt;- dmap.get(\"job1\") res2 &lt;- dmap.get(\"job2\") } yield (res1, res2) } example.unsafeRunSync() // res0: (String, String) = (\"success\", \"success\") The API has a lot of options available, including checking whether a value is being awaited as well as additional helper methods if you want the semantics of TryableDeferred instead. With a TryableDeferredMap, you can check if a value has been completed as well as conditionally delete values based on completion status. BE ADVISED: This is a rather low-level concurrency tool and you will want to thoroughly test your usage of this in order to not leak anything. Always be sure to delete values that have completed after some period of time, or make sure they expire with ExpiringRef."
    } ,    
    {
      "title": "EventState",
      "url": "/fs2-es/docs/eventstate",
      "content": "EventState An EventState is a common abstraction to help you manage best practices for dealing with event-sourced state. It can only be created with an initial value, and optionally a stream of events to “rehydrate” it by folding over them. Example for only initial state: import cats.effect._ import dev.rpeters.fs2.es.EventState val initialEventState = for { es &lt;- EventState[IO].initial[Int, Int](1)(_ + _) _ &lt;- es.doNext(1) result &lt;- es.get } yield result initialEventState.unsafeRunSync() // res0: Int = 2 Example for rehydrating state: import fs2.Stream val eventState = EventState[IO].initial[Int, Int](1)(_ + _) val hydratedState: IO[Int] = for { es &lt;- eventState _ &lt;- es.hydrate(Stream.emit(1)) result &lt;- es.get } yield result hydratedState.unsafeRunSync() // res1: Int = 2 The only way to change a value in an EventState is to supply it manually to doNext or supply a hydrating stream of events. In this way, an EventState is basically just a small wrapper around a cats.effect.concurrent.Ref that enforces an event-based access pattern. You can also “hook up” a stream of events to an EventState to hydrate it and get a stream of the resulting states back: val hookedUpStream = EventState[IO].initial[Int, Int](1)(_ + _).flatMap { es =&gt; Stream(1, 1, 1).through(es.hookup).compile.toList } hookedUpStream.unsafeRunSync() // res2: List[Int] = List(2, 3, 4) When using hookup, if you only have a single event stream going into your EventState then the resulting stream is guaranteed to have all possible state changes. SignallingEventState FS2 has some useful concurrency primitives in the form of SignallingRefs and Topics, among others. SignallingRef is useful when you want to use a variable as a signal where you continuously want the latest state change. SignallingEventState is similar in that it has properties just like a SignallingRef but it also allows you to continuously stream state changes from it. In particular, the following two methods are available: continuous - Get a stream that continuously emits the latest state at the time discrete - Get a stream of the latest state values, but only when the new state value is distinct from the previous value You should be aware that you are not guaranteed every single state change using these methods. It is equivallent to repeatedly calling get and maybe doing some stateful filtering after. If you actually want every single resulting state, you can use a single input stream with the hookup pipe. Or, you can use EventStateTopic and subscribe. EventStateTopic EventStateTopic is a variant of EventState that allows you to subscribe to all new state changes, just like an FS2 Topic. It has a new method subscribe that returns a stream of all state changes from the moment of subscription, beginning with its current value. You can also hookupAndSubscribe which will concurrently apply all events from the current stream, as well as subscribe and return all events, including ones not from the input stream. This can be very useful if you have multiple spots where you want to “listen for” new state changes and get all of them, instead of just the latest one. The downside of doing this approach is it is less efficient, as every single state change must be processed instead of just the latest one available, but sometimes that is the exact semantic that you want. If you are debugging, look into ReplayableEventState from the testing package, which is based on EventStateTopic."
    } ,    
    {
      "title": "EventStateCache",
      "url": "/fs2-es/docs/eventstatecache",
      "content": "EventStateCache Now that we have abstractions for both event-sourced state and timed lifetime management, we can put the two together and automatically manage the lifetimes of EventState with EventStateCache. EventStateCache acts as a repository interface for generic event-sourced state. It works similarly to a concurrent Map with each one of your EventStates held behind a key. What makes EventStateCache special is that it understands how to create new states, read them from your event log, and manage their lifetimes for efficiency. To create an EventStateCache, you need several functions and values defined that you plug into it. Here are all of the parameters necessary, with description: import cats.Applicative import cats.effect.IO import dev.rpeters.fs2.es.EventStateCache import fs2.Stream import scala.concurrent.duration._ // Our event-sourced state. Each user has a name and a point value. // We will be incrementing the user's points through events keyed to that user. case class User(name: String, points: Int) def fakeEventLog[F[_]] = Stream[F, Int](1, 1, 1) // Function #1 - Defines how you create an \"initial state\" given a key. // Don't worry about data that is not contained within the key at this stage. // Those should be modifiable as events - remember, every single change to state should be an event. def initializer(k: String): User = User(k, 0) // Function #2 - Defines how you restore state by reading in events by-key. // In a real application this will likely be a query or reading from a file/stream/topic and filtering by key. def keyHydrator[F[_]](k: String): Stream[F, Int] = if (k == \"ExistingUser\") fakeEventLog[F] else Stream.empty // Function #3 - Defines how you apply event to state. // This is exactly the same as the function used when creating an `EventState` manually. def eventProcessor(event: Int, state: User): User = state.copy(points = state.points + event) // Function #4 - An optional function to check that state for a given key already exists in your event log. // By default, this function is defined as testing that your `keyHydrator` function returns at least one event. // If you define this function, you can provide a more optimized way to check that a key already exists in your event log. // You can also disable the functionality entirely by returning `false`. def existenceCheck[F[_]: Applicative](k: String): F[Boolean] = if (k == \"ExistingUser\") Applicative[F].pure(true) else Applicative[F].pure(false) // Lastly we need a time-to-live duration for all states. val ttl = 2.minutes Finally, we can create an EventStateCache as follows: val cacheF: IO[EventStateCache[IO, String, Int, User]] = EventStateCache[IO].rehydrating(initializer)(keyHydrator[IO])(eventProcessor)(ttl, existenceCheck[IO]) Lets use this as a building block to write a basic event-sourced program: import fs2.Pure // An event type we can use to help initialize state for users. case class UserCreatedEvent(name: String) val usersToCreate: Stream[Pure, UserCreatedEvent] = Stream(\"FirstUser\", \"SecondUser\", \"ThirdUser\").map(UserCreatedEvent) val fullProgram = cacheF.flatMap { cache =&gt; // Because our existence check will fail for these, it should initialize these three with 0 points. val initializeNewUsers = usersToCreate.evalTap(u =&gt; cache.add(u.name)).compile.drain // Our hydrate function will be used when we call `.use` on our cache. val getExistingUser = cache.use(\"ExistingUser\")(es =&gt; es.get) // We'll create a stream that gives all users 5 points. // `hookup` is a `Pipe` that passes our events through to the underlying `EventState` by-key. // Also see: `hookupKey` for a key-specific pipe. val pointsByKey = usersToCreate.map(k =&gt; k.name -&gt; 5) val addToEachUser = pointsByKey.through(cache.hookup).compile.toList // Gives us the result of loading in an existing user as well as the result of applying events to all of our new users. for { _ &lt;- initializeNewUsers existing &lt;- getExistingUser list &lt;- addToEachUser } yield (existing, list) } fullProgram.unsafeRunSync() // res0: (Option[User], List[(String, Option[User])]) = ( // Some(value = User(name = \"ExistingUser\", points = 3)), // List( // (\"FirstUser\", Some(value = User(name = \"FirstUser\", points = 5))), // (\"SecondUser\", Some(value = User(name = \"SecondUser\", points = 5))), // (\"ThirdUser\", Some(value = User(name = \"ThirdUser\", points = 5))) // ) // ) As you would expect, these states in memory are only kept for the specified duration of 2 minutes. After it has been 2 minutes since the last usage, it will try your “hydrator” function to retrieve a stream of events to recreate the current state for your entity. Be sure, then, to have some kind of store for your events as they come in so that they can be properly retrieved."
    } ,    
    {
      "title": "ExpiringRef",
      "url": "/fs2-es/docs/expiringref",
      "content": "ExpiringRef Not directly related to events, but a useful primitive nonetheless, an ExpiringRef is a concurrently available value that expires after a certain period of time. When using event sourcing in particular, it can be helpful to “cache” event state in memory so that your application is not continuously reading from the event log every time it needs the latest state for something. This abstraction uses an internal timer that resets after each use so that lifetime management of your state is automated. Here is a simple example: import cats.effect.IO import dev.rpeters.fs2.es.data.ExpiringRef import scala.concurrent.ExecutionContext.global import scala.concurrent.duration._ implicit val cs = IO.contextShift(global) implicit val timer = IO.timer(global) val timedRef = for { res &lt;- ExpiringRef[IO].timed(1, 2.seconds) firstResult &lt;- res.use(i =&gt; IO.pure(i + 1)) _ &lt;- res.expired secondResult &lt;- res.use(i =&gt; IO.pure(i + 2)) } yield (firstResult, secondResult) timedRef.unsafeRunSync() // res0: (Option[Int], Option[Int]) = (Some(value = 2), None) There is also a variant ExpiringRef[F].uses that lets you specify a maximum number of uses, but you may find the timed variant to be more practical for event sourcing."
    } ,    
    {
      "title": "Home",
      "url": "/fs2-es/",
      "content": "FS2-ES This is a small library to encode event-sourcing patterns using FS2, a streaming library in Scala. The library is polymorphic using Cats Effect, so you can use it with any effect type you want that implements cats.effect.Concurrent. To use, add the library to your build.sbt like so: libraryDependencies += \"dev.rpeters\" %% \"fs2-es\" % \"&lt;latest-version&gt;\" libraryDependencies += \"dev.rpeters\" %% \"fs2-es-testing\" % \"&lt;latest-version&gt;\" //Test module Currently Scala 2.12 and 2.13 are both supported. Project is built for Scala JVM and ScalaJS 1.4+. Features This library has three core focuses: State Management - Use “EventState” to model event-driven state that is safe, concurrent, and with no boilerplate. Event Sourcing - Manage entities using event sourcing patterns with “EventStateCache”, a standard repository interface. Test Utilities - Utilize time-travel debugging features and other goodies to analyze your state as it changes. Click any of the links above to go to the relevant parts of the documentation on our microsite. API Docs Core Testing"
    } ,    
    {
      "title": "Introduction",
      "url": "/fs2-es/docs/",
      "content": "Introduction Modelling state with events can be simple to understand, but difficult to master. You may have heard of “event sourcing”, or perhaps the “Flux” and “Redux” models of application architecture in JavaScript. In each of these, there lies a common thread of having event-driven systems where the only way to modify state is by taking in a linear sequence of events as they occur. One easy way to model this is using fold in functional languages. For example, here is a basic way to get some event-driven state using “fold”, written using the FS2 streaming library: import cats.implicits._ import fs2.{Pipe, Pure, Stream} //A function that takes our stream and computes a final result state def buildState: Pipe[Pure, Int, Int] = s =&gt; s.fold(0)(_ + _) val incomingEvents = Stream(1, 2, 3) //Now we run our program and observe the resulting state incomingEvents.through(buildState).compile.last // res0: cats.package.Id[Option[Int]] = Some(value = 6) There are several advantages to building your state from events, especially if they hold the following properties: Events are immutable and never change Events represent things that have happened, and not intentions to perform a specific action The order of events is strictly linear for any “aggregate root” (a single unit of state that does not depend on any parent relationship). In trying to achieve these properties, certain patterns emerge that this library hopes to properly encode. I personally take the view that overly-opinionated frameworks around event sourcing are a bad idea as they not only constrain the entire design of your progam but they also make it harder to be more flexible with the definition of event-driven state that you happen to employ. For example, many frameworks make an opinionated decision about where you store your event log. This library has nothing to say about persistence, only functionality related to restoring and managing the lifetimes of state from events. You can very easily build your own event log just by serializing events and putting them in a database table, Apache Kafka or Pulsar, or even to a raw file for example, and in my opinion that is the easiest part of this to “get right” on your own. This library chooses to focus on some of the more easily composable parts of event sourcing. To that end, it comes with a few useful utilities you should get to know. Start with EventState in the sidebar and continue from there. What to use? If you are doing a small event-sourced program and maybe only have a few, finite sources of event-sourced state, you can get by with only EventState just fine. If you have a number that you are quite confident should fit in memory, but might be dynamic for other reasons, make a MapRef[K, EventState] or use some other pattern/structure to organize your state. If you need custom lifetime management built on top of that, feel free to write your own structures using ExpiringRef as well on top of that, or on the side as-needed. Lastly, if you need all of that plus a key/value repository interface for your event-sourced state, EventStateCache should give you everything you need at once. It not only handles retrieving your state from your event log as you define it, but it also makes sure that you do not waste precious time or resources re-running the same event log queries by caching state in-memory. If you do not need “the full package” you should very easily be able to build what you need with each of the smaller parts that make up one EventStateCache. Try it out, see what works for you, and if you were able to build something that fit your use cases better with it, be sure to let me know! Happy event sourcing!"
    } ,      
    {
      "title": "MapRef",
      "url": "/fs2-es/docs/mapref",
      "content": "MapRef MapRef is used internally as a small wrapper around an immutable Map inside of a cats.effect.concurrent.Ref. You can use it as a map of concurrent values that you can access across your application. import cats.effect.IO import dev.rpeters.fs2.es.data.MapRef //Create a concurrent MapRef val example = MapRef[IO].empty[String, String].flatMap { map =&gt; for { _ &lt;- map.add(\"key\" -&gt; \"value\") //Upsert a value to the map res1 &lt;- map.get(\"key\") _ &lt;- map.del(\"key\") res2 &lt;- map.get(\"key\") } yield (res1, res2) } example.unsafeRunSync() // res0: (Option[String], Option[String]) = (Some(value = \"value\"), None) It has a couple handy extra operators besides just add/get/del operations that you might find useful. MapRef#modify allows you to atomically modify the contents of an entry by-key and return a result value: val exampleModify = MapRef[IO].of(Map(\"key\" -&gt; \"value\")).flatMap { map =&gt; for { resFromModify &lt;- map.modify(\"key\")(v =&gt; s\"$v but modified\" -&gt; \"result\") resFromGet &lt;- map.get(\"key\") } yield (resFromModify, resFromGet) } exampleModify.unsafeRunSync() // res1: (Option[String], Option[String]) = ( // Some(value = \"result\"), // Some(value = \"value but modified\") // ) As well as MapRef#upsertOpt that conditionally either modifies or upserts a value for a given key: val exampleUpsertOpt = MapRef[IO].of(Map(\"key\" -&gt; \"value\")).flatMap { map =&gt; //A helper function for either modifying or inserting a new value def upsertFunc(optV: Option[String]): (String, String) = optV match { case Some(_) =&gt; \"value exists\" -&gt; \"value exists result\" case None =&gt; \"new value\" -&gt; \"new value result\" } for { upsertExisting &lt;- map.upsertOpt(\"key\")(upsertFunc) upsertNew &lt;- map.upsertOpt(\"newKey\")(upsertFunc) resExisting &lt;- map.get(\"key\") resNew &lt;- map.get(\"newKey\") } yield (upsertExisting, upsertNew, resExisting, resNew) } exampleUpsertOpt.unsafeRunSync() // res2: (String, String, Option[String], Option[String]) = ( // \"value exists result\", // \"new value result\", // Some(value = \"value exists\"), // Some(value = \"new value\") // )"
    } ,      
    {
      "title": "Testing",
      "url": "/fs2-es/docs/testing/",
      "content": "Testing Using FS2-ES, you might want to be able to more easily inspect the history and contents of your state. A concept that appears in event-based programming is the idea of “time-travel debugging”, or the ability to go forward and back in time. Because EventState enforces a linear, event-driven access pattern, that means that we are able to store all modifications to state and replay them, giving you access to all possible states that have been achieved. If you install the fs2-es-testing module, you’ll be able to use ReplayableEventState which is an extension of EventStateTopic with special testing and debugging methods. First, add the testing module to your project (available for ScalaJS 1.x as well): libraryDependencies += \"dev.rpeters\" %% \"fs2-es-testing\" % &lt;current-version&gt; You can create one the exact same way as other EventState implementations, either with an initial value or a stream of events to “hydrate” it with. import cats.effect._ import cats.implicits._ import dev.rpeters.fs2.es.testing.ReplayableEventState import scala.concurrent.ExecutionContext.global //Allows us to do concurrent actions, not required if using IOApp implicit val cs = IO.contextShift(global) //Creates a new ReplayableEventState on each invocation that adds integers to state val newState = ReplayableEventState[IO].initial[Int, Int](0)(_ + _) From here, we can start accumulating events as normal, and it will work just like any other EventStateTopic. Getting The Event List For testing, you may want to know what the current event list is, so lets accumulate some events and get them back: val eventsTest = for { es &lt;- newState //Make the event state _ &lt;- es.doNext(1) //Add some events _ &lt;- es.doNext(2) _ &lt;- es.doNext(3) state &lt;- es.get //Check the current state events &lt;- es.getEvents //Check the list of events } yield (state, events) eventsTest.flatMap { case (state, events) =&gt; IO(println(s\"State: $state\")) &gt;&gt; IO(println(s\"Events: $events\")) }.unsafeRunSync() // State: 6 // Events: Chain(1, 2, 3) You may have noticed that the events are returned as a Chain. That’s an implementation detail, and you can treat it similarly to a List or turn it into one by calling .toList as-needed. For information on how Chain works or why you would want to use it, see the Cats Chain documentation. The gist is, it works a lot like List but it performs much better in append-only scenarios. If you don’t need the entire list of events but you just want the event count, you can call es.getEventCount. Seeking By Index Sometimes when debugging you might want to go “backwards” to a previous state. You can seek backwards by specifying the index of the state you would like to go to, or optionally specifying an offset to seek forwards and backwards. The available methods for this are: seekTo(n) - Seek to index n seekToBeginning - Alias for seekTo(0) seekBackBy(n) - Goes back n states ago. seekForwardBy(n) - Goes forward n states ahead of the current state. Seeking is a non-destructive action which means you can do it safely without destroying the current event history. If you do append a new event to the current state, it will drop all later events (if any), so be sure to save them if you want to replay them later. val seekTest = for { es &lt;- newState //Make the event state _ &lt;- es.doNext(1) //Add some events _ &lt;- es.doNext(2) _ &lt;- es.doNext(3) oldState &lt;- es.get //Check the current state oldEvents &lt;- es.getEvents //Check the list of events newState &lt;- es.seekTo(1) //Go to the second state, after applying the first event (1) sameEvents &lt;- es.getEvents //Get the event list, to show it is non-destructive } yield (oldState, oldEvents, newState, sameEvents) seekTest.flatMap { case (oldState, events, newState, sameEvents) =&gt; IO(println(s\"Old state: $oldState\")) &gt;&gt; IO(println(s\"Old Events: $events\")) &gt;&gt; IO(println(s\"New state: $newState\")) &gt;&gt; IO(println(s\"Same Events: $sameEvents\")) }.unsafeRunSync() // Old state: 6 // Old Events: Chain(1, 2, 3) // New state: 1 // Same Events: Chain(1, 2, 3) Resetting state There are special reset and resetInitial methods now available that allow you to completely wipe the current state including the list of events. Calling reset allows you to go back to the first accumulated state, while resetInitial allows you to provide a new initial state to reset to. val resetTest = for { es &lt;- newState //Make the event state _ &lt;- es.doNext(1) //Add some events _ &lt;- es.doNext(2) _ &lt;- es.doNext(3) latestState &lt;- es.get //Check the current state resettedState &lt;- es.reset //Reset to zero resettedEvents &lt;- es.getEvents //Get events, to show it is cleared newInitialState &lt;- es.resetInitial(5) //Set the first state to 5 newInitialEvents &lt;- es.getEvents //Check events again, which should still be empty } yield (latestState, resettedState, resettedEvents, newInitialState, newInitialEvents) resetTest.flatMap { case (ls, rs, re, nis, nie) =&gt; IO(println(s\"Latest State: $ls\")) &gt;&gt; IO(println(s\"Resetted State: $rs\")) &gt;&gt; IO(println(s\"Resetted Events: $re\")) &gt;&gt; IO(println(s\"New Initial State: $nis\")) &gt;&gt; IO(println(s\"New Initial Events: $nie\")) }.unsafeRunSync() // Latest State: 6 // Resetted State: 0 // Resetted Events: Chain() // New Initial State: 5 // New Initial Events: Chain() State changes and subscriptions Because this is based on EventStateTopic, you can subscribe to receive all state changes. You might have noticed that the methods for resetting and seeking state also return the resulting state back. Each time you call one of these methods, you will also publish the resulting state to all subscribers. This decision was made intentionally to allow for reactive debugging in environments such as ScalaJS where you might be using this as a reactive state store (think Flux/Redux)."
    }    
  ];

  idx = lunr(function () {
    this.ref("title");
    this.field("content");

    docs.forEach(function (doc) {
      this.add(doc);
    }, this);
  });

  docs.forEach(function (doc) {
    docMap.set(doc.title, doc.url);
  });
}

// The onkeypress handler for search functionality
function searchOnKeyDown(e) {
  const keyCode = e.keyCode;
  const parent = e.target.parentElement;
  const isSearchBar = e.target.id === "search-bar";
  const isSearchResult = parent ? parent.id.startsWith("result-") : false;
  const isSearchBarOrResult = isSearchBar || isSearchResult;

  if (keyCode === 40 && isSearchBarOrResult) {
    // On 'down', try to navigate down the search results
    e.preventDefault();
    e.stopPropagation();
    selectDown(e);
  } else if (keyCode === 38 && isSearchBarOrResult) {
    // On 'up', try to navigate up the search results
    e.preventDefault();
    e.stopPropagation();
    selectUp(e);
  } else if (keyCode === 27 && isSearchBarOrResult) {
    // On 'ESC', close the search dropdown
    e.preventDefault();
    e.stopPropagation();
    closeDropdownSearch(e);
  }
}

// Search is only done on key-up so that the search terms are properly propagated
function searchOnKeyUp(e) {
  // Filter out up, down, esc keys
  const keyCode = e.keyCode;
  const cannotBe = [40, 38, 27];
  const isSearchBar = e.target.id === "search-bar";
  const keyIsNotWrong = !cannotBe.includes(keyCode);
  if (isSearchBar && keyIsNotWrong) {
    // Try to run a search
    runSearch(e);
  }
}

// Move the cursor up the search list
function selectUp(e) {
  if (e.target.parentElement.id.startsWith("result-")) {
    const index = parseInt(e.target.parentElement.id.substring(7));
    if (!isNaN(index) && (index > 0)) {
      const nextIndexStr = "result-" + (index - 1);
      const querySel = "li[id$='" + nextIndexStr + "'";
      const nextResult = document.querySelector(querySel);
      if (nextResult) {
        nextResult.firstChild.focus();
      }
    }
  }
}

// Move the cursor down the search list
function selectDown(e) {
  if (e.target.id === "search-bar") {
    const firstResult = document.querySelector("li[id$='result-0']");
    if (firstResult) {
      firstResult.firstChild.focus();
    }
  } else if (e.target.parentElement.id.startsWith("result-")) {
    const index = parseInt(e.target.parentElement.id.substring(7));
    if (!isNaN(index)) {
      const nextIndexStr = "result-" + (index + 1);
      const querySel = "li[id$='" + nextIndexStr + "'";
      const nextResult = document.querySelector(querySel);
      if (nextResult) {
        nextResult.firstChild.focus();
      }
    }
  }
}

// Search for whatever the user has typed so far
function runSearch(e) {
  if (e.target.value === "") {
    // On empty string, remove all search results
    // Otherwise this may show all results as everything is a "match"
    applySearchResults([]);
  } else {
    const tokens = e.target.value.split(" ");
    const moddedTokens = tokens.map(function (token) {
      // "*" + token + "*"
      return token;
    })
    const searchTerm = moddedTokens.join(" ");
    const searchResults = idx.search(searchTerm);
    const mapResults = searchResults.map(function (result) {
      const resultUrl = docMap.get(result.ref);
      return { name: result.ref, url: resultUrl };
    })

    applySearchResults(mapResults);
  }

}

// After a search, modify the search dropdown to contain the search results
function applySearchResults(results) {
  const dropdown = document.querySelector("div[id$='search-dropdown'] > .dropdown-content.show");
  if (dropdown) {
    //Remove each child
    while (dropdown.firstChild) {
      dropdown.removeChild(dropdown.firstChild);
    }

    //Add each result as an element in the list
    results.forEach(function (result, i) {
      const elem = document.createElement("li");
      elem.setAttribute("class", "dropdown-item");
      elem.setAttribute("id", "result-" + i);

      const elemLink = document.createElement("a");
      elemLink.setAttribute("title", result.name);
      elemLink.setAttribute("href", result.url);
      elemLink.setAttribute("class", "dropdown-item-link");

      const elemLinkText = document.createElement("span");
      elemLinkText.setAttribute("class", "dropdown-item-link-text");
      elemLinkText.innerHTML = result.name;

      elemLink.appendChild(elemLinkText);
      elem.appendChild(elemLink);
      dropdown.appendChild(elem);
    });
  }
}

// Close the dropdown if the user clicks (only) outside of it
function closeDropdownSearch(e) {
  // Check if where we're clicking is the search dropdown
  if (e.target.id !== "search-bar") {
    const dropdown = document.querySelector("div[id$='search-dropdown'] > .dropdown-content.show");
    if (dropdown) {
      dropdown.classList.remove("show");
      document.documentElement.removeEventListener("click", closeDropdownSearch);
    }
  }
}
