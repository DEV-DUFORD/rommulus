---
layout: default
title: RomMulus Privacy Notice
permalink: /privacy/
---

# RomMulus Privacy Notice

**Effective date:** August 12, 2026

RomMulus is an open-source Android TV client for connecting to a RomM server selected and
controlled by the user. The RomMulus project does not operate a service that receives your
account information, game library, or gameplay data.

## Information handled by RomMulus

RomMulus handles the following information to provide its features:

- The address of the RomM server you choose.
- Your RomM username, password during sign-in, access token, and server session information.
- A randomly generated installation identifier and the device identifier assigned by your RomM
  server.
- Library information received from your server, including game metadata, cover artwork, ROM
  files, firmware files, collections, favorites, and search results.
- Game saves, save states, play-session information, controller mappings, app preferences, and
  cache records.
- Basic network availability information used to schedule synchronization.

RomMulus does not request precise or approximate location, contacts, microphone, camera, or shared
storage access.

## How information is used

This information is used only to:

- Connect and authenticate to the RomM server you select.
- Display and search your game library.
- Download games and required firmware for local play.
- Play games on your device and synchronize saves and play-session information with your server.
- Remember app, display, and controller settings.

## Storage on your device

RomMulus stores the selected server address, username, session information, device identifiers,
settings, downloaded content, and game-save data in the app's private storage. Access tokens are
encrypted with a key held by the Android Keystore. Passwords are used during sign-in and are not
stored after the sign-in attempt. Android backup is disabled for the app.

You can remove information stored by RomMulus by signing out where applicable, clearing the app's
storage in Android settings, or uninstalling the app.

## Transfers to your RomM server

RomMulus sends requests directly from your device to the RomM server address you provide. These
requests can include credentials or access tokens, device identifiers, library actions,
play-session information, and save files. Data retained by that server is controlled by the
server's owner and is governed by the server owner's privacy and retention practices. Contact
your RomM server administrator to access or delete server-side information.

RomMulus requires HTTPS for public server addresses. It also permits HTTP connections to local,
private-network servers after displaying a warning. Information sent over an HTTP connection is
not encrypted in transit.

## Sharing, advertising, and analytics

The RomMulus project does not receive or sell your personal information. RomMulus does not include
advertising, analytics, tracking, or crash-reporting SDKs. It does not share information with data
brokers or advertising providers.

Network operators and the owner or hosting provider of the RomM server you select may process
connection information as part of operating their networks and server. Their practices are outside
the control of the RomMulus project.

## Children's privacy

RomMulus is not directed to children. The app does not provide games; the owner of each RomM server
controls the content available through that server.

## Changes to this notice

This notice may be updated when RomMulus features or data practices change. Updates will be posted
on this page with a revised effective date.

## Contact

For privacy questions or requests concerning RomMulus itself, open an issue in the
[RomMulus GitHub repository](https://github.com/DEV-DUFORD/rommulus/issues). For information stored
by a RomM server, contact that server's administrator.

