# react-alarms

A Discord bot that allows people to receive a DM alarm before an event by reacting to a forum post.

Discord forums can be used as a programme guide within Discord by having a post per item.
This bot watches forum channels for new posts and, if it can parse out a time from the title, adds
an emoji react.
If other people also react with that same emoji, it will send them a DM a configured amount of time
before the time in the title of the forum post.

This bot was written for a specific event.
This means that while there are some places where it can be configured, it may not be flexible
enough to meet your needs.  

## Forum structure
The bot assumes that you have a forum channel per day for the event, and that each event has a time
at the start of the title.

Titles must match the following regular expression.
If it doesn't, the post will be ignored and no react added.

`^\W*(\d{1,2})(?:[:. ](\d{2}))?(?:\s*(am|a\.m\.?|pm|p\.m\.?))?(?:\W+|$)`

Examples include:
 * `13:00`
 * `1pm`
 * `9.15 a.m.`

## Permissions
The bot needs the following permissions in the Discord server:
 * Read Messages/View Channels
 * Manage Messages
 * Manage Threads
 * Add Reactions

## Configuration
### `.env`
The credentials are stored either in an environment variable, or a `.env` file.
The `.env` file has the format `<key>=<value>`.

See `.env.example` for documentation.

### `config.toml`
The config file uses the TOML format.

The filename can be whatever you like, and is passed to the application as a command line parameter.
The convention is to call it `config.toml`.

See `config.toml.example` for documentation.

## Database
State is stored in the database file on disk.
Persisting the data allows the bot to recover if it is restarted.
It will look for any posts added, updated or deleted while it was not running, and it will trigger
any alarms that occurred while it was not running (assuming it is not past the
`max_mins_after_to_notify` configuration).

## Building
`./gradlew shadowJar`

## Running
Before your first run, you will need to create an empty database:

`java -cp react-alarms-1.0-SNAPSHOT-all.jar com.ajanuary.reactalarms.CreateDatabase config.toml`

You can then start the bot using:

`java -cp react-alarms-1.0-SNAPSHOT-all.jar com.ajanuary.reactalarms.RunBot config.toml`