# ADEO RGB bulb: device information

This procedure collects Bluetooth and, if available, BLE GATT information
with BlueZ `bluetoothctl` 5.72. It has not yet been tested with this bulb.
The filename is not proof of the device's manufacturer or protocol.

Replace `<bulb-address>` with the bulb's address locally. `bluetoothctl`
uses colon-separated addresses (`XX:XX:XX:XX:XX:XX`), not hyphens.
Do not store the real address or other personal device identifiers in Git.

## Discover and inspect the device

1. Turn on the bulb and close its mobile application to release any existing
	connection. Open a terminal:

	```bash
	bluetoothctl
	```

2. Inspect the local Bluetooth controller:

	```text
	list
	show
	```

	If no controller appears, resolve the adapter or BlueZ service issue
	first. If `Powered: no` appears, run `power on` to enable the computer's
	Bluetooth radio.

3. Start BLE discovery and wait for the bulb to appear:

	```text
	scan le
	```

	After 10 to 20 seconds, stop discovery and inspect the bulb:

	```text
	scan off
	info <bulb-address>
	```

	If the bulb is absent, try `scan on` instead of `scan le`, then stop
	scanning again. Absence from a BLE scan does not prove that it uses
	Bluetooth Classic. BlueZ may also retain cached information.

	Record available fields such as Name, Alias, AddressType, Paired,
	Connected, UUIDs, ManufacturerData and ServiceData. Missing fields are
	not evidence that the corresponding feature is unsupported.

## Connect and inspect BLE attributes

1. Connect without requesting pairing:

	```text
	connect <bulb-address>
	```

	Wait for service discovery to finish, ideally with `ServicesResolved:
	yes`. Inspect the updated device information:

	```text
	info <bulb-address>
	```

2. Enter the GATT menu and list this device's attributes:

	```text
	menu gatt
	list-attributes <bulb-address>
	```

	GATT exposes BLE services, characteristics and descriptors. An UUID
	identifies an attribute; it does not by itself reveal a proprietary
	RGB command format. A Bluetooth Classic-only device will not expose
	this BLE interface.

3. Select a characteristic using its full object path from the listing:

	```text
	select-attribute <characteristic-path>
	attribute-info
	```

	Record its UUID, service, Flags and any displayed value. Repeat for
	other relevant characteristics. Redact Bluetooth addresses embedded
	in object paths before saving output in the repository.

4. Optionally, if Flags includes `read`, read the selected characteristic:

	```text
	read
	```

	Record the returned bytes or the exact error. If authentication is
	required, stop and review the pairing requirement before proceeding.
	Do not send `write` commands or guess RGB payloads. This procedure
	does not enable notifications or request pairing.

5. Leave the GATT menu, disconnect and exit:

	```text
	back
	disconnect <bulb-address>
	quit
	```

## Results to collect

- Date, bulb model and mobile application name, if known.
- Redacted output of `info`, `list-attributes` and `attribute-info`.
- Read results, if attempted, and exact connection or permission errors.
- Observed facts separately from assumptions about RGB control.

No device observations or RGB commands have been verified yet. The next
step is to run this procedure and inspect the collected attributes before
attempting any control operation.
