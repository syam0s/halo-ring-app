# Halo – personal app for the Ravoz Halo Ring RZ02

A private Android app that talks to the ring directly over Bluetooth (no cloud).
The ring is a QRing/Colmi-family device (hardware `RT08RZ`), so it uses the openly documented
Colmi R0x protocol (credit: Gadgetbridge and tahnok/colmi_r02_client).

**Get the app:** open the latest release, download the `.apk`, and install it.
Every push to `main` builds a new version automatically.

Features: steps, heart rate, sleep stages, SpO2, stress, HRV, live HR/SpO2 measurement,
ring settings, find ring, hourly background sync, CSV export. All data stays on the phone.

**Version 2.1 (sleep first):** four sleep tabs open first: Last night, Tonight's plan (bedtime from
sleep debt, with a wind-down reminder), Sleep rhythm (bedtime regularity) and Overnight recovery
(heart rate, HRV and SpO2 through the night). The Ring tab has a touch test that shows whether
the ring reports touch or gestures.
