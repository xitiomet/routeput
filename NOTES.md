# THINGS TO REMEMBER

## Server to Client Messages
1. if the message is from the server to the client, the srcId should match the srcId of the client. this applies to propertyChange and ping messages

2. Many clients might have a very small amount of ram, packets should always be small (less then 4096 bytes)

3. All messages must travel through a channel. The Channel is a virtual packet server.
    1. "dstId" allows the targeting of one user in a channel
    2. collectors receive all the non targeted messages sent to the channel, and nobody else does.
    3. "where" can be used to target by session properties


# PROJECT GOALS

1. Message routing, one-to-many via collector and many-to-many as default

2. Key/Value storage on channel and session with property change listeners and auto-sync

3. Server to Server chaining - Implemented

4. Blob storage and retrieval
    BLOBFile & blob manager

5. Easy to extend RoutePutSession to represent virtual connections
    
    1. single incoming message method send()
    2. message listeners for outgoing messages

6. WHERE system and message filter
    1. Ability to specify a filter in the meta field that will only deliver the
       message to members with matching properties.