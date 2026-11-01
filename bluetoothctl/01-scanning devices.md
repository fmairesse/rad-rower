# bluetoothctl

`bluetoothctl` is the command-line interface for BlueZ, the Linux Bluetooth
stack. It can discover devices, manage pairing and connections, and inspect
Bluetooth Low Energy (BLE) services and characteristics.

This directory documents manual Bluetooth experiments before using Python
and Bleak. Version 5.72 is installed on the development machine; the scan
procedure below has not yet been tested with the rower.

## Scan for nearby devices

Scanning requires a working Bluetooth adapter and a running BlueZ service.
Nearby BLE devices must be advertising to be discovered; being within range
does not guarantee that a device will appear.

1. Open the interactive Bluetooth console from a Linux terminal:

   ```bash
   bluetoothctl
   ```

2. Inside the console, list the adapters and inspect the selected adapter:

   ```text
   list
   show
   ```

   If no controller is listed, resolve the adapter or BlueZ service issue
   before proceeding. If multiple adapters are listed, use
   `select <controller-address>` to choose one.

3. If `show` reports `Powered: no`, enable the local adapter:

   ```text
   power on
   ```

   This turns on the computer's Bluetooth radio, not the rower. If it fails,
   check whether Bluetooth is blocked or the adapter is unavailable.

4. Start a BLE-only scan:

   ```text
   scan le
   ```

   Watch the output for 10 to 20 seconds. Discovery messages typically
   include an address and, when available, a device name. `[NEW]` indicates
   a newly discovered device; `[CHG]` indicates a property update. Do not
   identify the rower from its name alone.

   To scan for both Bluetooth Classic and BLE devices instead, use
   `scan on` rather than `scan le`.

5. Stop scanning, then list the devices known to BlueZ:

   ```text
   scan off
   devices
   ```

   The list may include cached devices that are no longer nearby; it is not
   proof of current presence.

6. Exit the console:

   ```text
   quit
   ```

Use `help` inside the console for available commands. This procedure does
not pair with, connect to, or send control commands to the rower. Scanning
can involve radio exchanges and is not necessarily passive listening.
Redact personal device identifiers before committing discovery logs.
