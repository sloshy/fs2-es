---
layout: docs
title: Type Classes
permalink: /docs/typeclasses/
---
# Type Classes
To make certain features of this library possible, we need to abstract over certain capabilities of your domain.
This includes defining functions for applying events to state, extracting "keys" from events, and combinations of the two.

Currently, there are five type classes in this library:

* [`Driven`](driven.md) and `DrivenNonEmpty`, for defining how events apply to your state.
* [`Keyed`](keyed.md), for extracting keys from events.
* [`KeyedState`](/docs/type classes/keyedstate/) and `KeyedStateNonEmpty`, for combining keyed events and state.

Many of the more useful features in [`EventLog`](eventlog.md) and [`EventStateCache`](eventstatecache.md) utilize these type classes to make things a little easier.
When defining your events and state, be sure to define these type classes in their companion objects (or somewhere else you can easily import them) so that you can make the most of this library and its functionality for your use cases.
