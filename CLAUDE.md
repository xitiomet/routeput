# CLAUDE.md

Working notes for AI assistants collaborating on the routeput codebase.

## Project overview

Routeput is a lightweight WebSocket messaging + property-change server. It routes JSON
messages between clients on named channels. Every message carries a `__routeput` envelope
with routing metadata (`channel`, `srcId`, optional `dstId`, `msgId`, `type`, etc.). The
server is written in Java (with GraalVM `native-image` support) using Jetty for WebSocket
and HTTP, and `org.json` for JSON. There are companion client libraries in Java
(`RoutePutClient`), JavaScript (`routeput.js`), PHP, and Python.

## Repository layout

- `src/main/java/org/openstatic/routeput/` — server, session, channel, message, blob code.
    - `RoutePutServerWebsocket.java` — inbound WebSocket per client, dispatches by type.
    - `RoutePutChannel.java` — channel state, membership, broadcast routing.
    - `RoutePutMessage.java` — JSON envelope wrapper (`__routeput` accessors).
    - `RoutePutRemoteSession.java` — subconnection routing (via `dstId`/`srcId`).
    - `BLOBManager.java` / `BLOBFile.java` — blob storage, chunked transfer, have/need.
    - `client/RoutePutClient.java` — Java client library.
- `src/main/resources/routeput.js` — browser client library (shipped from JAR + `/`).
- `src/main/resources/*.html` — bundled UI pages.
- `src/main/resources/META-INF/native-image/` — GraalVM reachability config.
- `src/main/php/`, `src/main/python/` — additional client libraries.
- `src/deb/` — Debian packaging (Java and native builds).
- `channel/`, `blob/` — runtime state directories (channel props, blob storage).
- `pom.xml`, `mvnReinstall.sh`, `RunWithNativeImageAgent.cmd` / `.sh` — build/run helpers.

## Build and run

- Java compile: `mvn -q compile`
- Full package: `mvn -q package` (uses `pom.xml`)
- Local reinstall: `./mvnReinstall.sh`
- Native-image agent capture: `./runWithNativeImageAgent.sh` (writes to
    `target/graalvm-reachability-metadata/`)
- Default server port: `6144`; WebSocket path: `/channel/<channelName>/`.

## Message envelope conventions

- Every message JSON has a `__routeput` object with routing metadata.
- Standard fields: `srcId`, `channel`, `type`, `msgId`, optional `dstId`, `ref`.
- Request/response pattern: sender sets `msgId`; responder uses `setResponse(...)`
    which fills `type=response`, `response=<name>`, `ref=<msgId>`, and copies `channel`.
- Message types used today: `connectionId`, `ping`/`pong`, `request`/`response`,
    `propertyChange`, `ConnectionStatus`, `blob`, `error`/`info`/`warning`.

## Blob transmission model

- The server is the **sole storage and distributor** of blobs. Clients never send blobs
    directly to other clients.
- Have/need negotiation uses `type: request, request: "blobCheck"` and
    `type: response, response: "blobCheck"`. The `blob` type is reserved for chunk data
    only.
- Chunks are `type: blob` with `name`, `i`, `of`, `data`, and optional `context`. The
    server does NOT broadcast chunks to a channel — it accumulates, saves to the
    channel's blob folder, then re-emits chunks per member via `distributeChannelBlob`,
    each with its own `blobCheck` handshake.
- `BLOBManager` is **opt-in**. Only a caller that invokes `BLOBManager.init(settings)`
    with a non-null `settings` receives blobs and stores them. Uninitialized processes
    (typical `RoutePutClient` library users) silently drop chunk data and answer any
    `blobCheck` request with `state: "have"` so senders never waste bandwidth.
- On init, `BLOBManager` sweeps `blobStorageRoot` and deletes any file older than
    `blobStorageTimeout` seconds (default `30 * 24 * 60 * 60` = 30 days). Set the
    timeout to `0` to disable the sweep.
- MD5 (hex) + byte size uniquely identify a blob for the have/need check. Java uses
    upper-case hex, JS lower-case; comparisons are case-insensitive.
- JS `blobCache` (`context:name → {md5, size, blob}`) satisfies `requestBlob(...)`
    from the local cache without a server round-trip.

## Coding conventions

- Prefer minimal, targeted edits. Don't refactor code you weren't asked to change.
- Do not add doc comments, type annotations, or logging that weren't there before
    unless the surrounding style already has them.
- Comments should state what the code cannot show on its own — keep them to one line
    when possible. Don't restate what the next line does.
- Match existing brace style, spacing, and naming in each file.
- When adding fields to `RouteputConnection` in `routeput.js`, also declare the field
    at the top of the class alongside the existing declarations.
- When changing message flows, update both Java and JS ends together; the two
    protocols must stay in lockstep.

## Rules and goals

<!-- Fill in project-specific rules and current goals here. Examples:
- Rule: chunk size stays at 4096 bytes for base64 blob transfer.
- Rule: no direct client-to-client blob traffic; the server always mediates.
- Goal: keep native-image reachability metadata current with new reflective code paths.
- Goal: add a timeout/cleanup for stale `pendingSends` entries in `BLOBManager`.
- Goal: wire `blobCheck` into the PHP and Python client libraries.
-->

## Known gaps / follow-ups

- `BLOBManager.pendingSends` has no timeout; a peer that never responds to a
    `blobCheck` leaks an entry. Consider a scheduled sweep.
- The PHP and Python clients have not yet been updated for the request/response
    `blobCheck` protocol.
- No unit test coverage for the blob handshake; verification is currently manual.
