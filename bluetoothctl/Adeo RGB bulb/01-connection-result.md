# RGB smart bulb: connection result

Recorded on 2026-11-01 from the connection output supplied by the user.
Procedure: [device information guide](README.md).

The Bluetooth address is replaced with `<bulb-address>` in messages and
`dev_REDACTED` in BlueZ object paths. UUIDs and attribute path suffixes
are preserved exactly as reported.

## Observed output

```text
Attempting to connect to <bulb-address>
[CHG] Device <bulb-address> Connected: yes
[RGB smart bulb]# Connection successful
[RGB smart bulb]# [NEW] Primary Service (Handle 0x0000)
	/org/bluez/hci0/dev_REDACTED/service0001
	00001801-0000-1000-8000-00805f9b34fb
	Generic Attribute Profile
[RGB smart bulb]# [NEW] Characteristic (Handle 0x0000)
	/org/bluez/hci0/dev_REDACTED/service0001/char0002
	00002a05-0000-1000-8000-00805f9b34fb
	Service Changed
[RGB smart bulb]# [NEW] Descriptor (Handle 0x0000)
	/org/bluez/hci0/dev_REDACTED/service0001/char0002/desc0004
	00002902-0000-1000-8000-00805f9b34fb
	Client Characteristic Configuration
[RGB smart bulb]# [NEW] Characteristic (Handle 0x0000)
	/org/bluez/hci0/dev_REDACTED/service0001/char0005
	00002b2a-0000-1000-8000-00805f9b34fb
	Database Hash
[RGB smart bulb]# [NEW] Characteristic (Handle 0x0000)
	/org/bluez/hci0/dev_REDACTED/service0001/char0007
	00002b29-0000-1000-8000-00805f9b34fb
	Client Supported Features
[RGB smart bulb]# [NEW] Primary Service (Handle 0x0000)
	/org/bluez/hci0/dev_REDACTED/service0009
	0000180a-0000-1000-8000-00805f9b34fb
	Device Information
[RGB smart bulb]# [NEW] Characteristic (Handle 0x0000)
	/org/bluez/hci0/dev_REDACTED/service0009/char000a
	00002a29-0000-1000-8000-00805f9b34fb
	Manufacturer Name String
[RGB smart bulb]# [NEW] Characteristic (Handle 0x0000)
	/org/bluez/hci0/dev_REDACTED/service0009/char000c
	00002a24-0000-1000-8000-00805f9b34fb
	Model Number String
[RGB smart bulb]# [NEW] Characteristic (Handle 0x0000)
	/org/bluez/hci0/dev_REDACTED/service0009/char000e
	00002a25-0000-1000-8000-00805f9b34fb
	Serial Number String
[RGB smart bulb]# [NEW] Characteristic (Handle 0x0000)
	/org/bluez/hci0/dev_REDACTED/service0009/char0010
	00002a28-0000-1000-8000-00805f9b34fb
	Software Revision String
[RGB smart bulb]# [NEW] Characteristic (Handle 0x0000)
	/org/bluez/hci0/dev_REDACTED/service0009/char0012
	00002a26-0000-1000-8000-00805f9b34fb
	Firmware Revision String
[RGB smart bulb]# [NEW] Characteristic (Handle 0x0000)
	/org/bluez/hci0/dev_REDACTED/service0009/char0014
	00002a27-0000-1000-8000-00805f9b34fb
	Hardware Revision String
[RGB smart bulb]# [NEW] Primary Service (Handle 0x0000)
	/org/bluez/hci0/dev_REDACTED/service001b
	0000a100-1115-1000-0001-617573746f6d
	Vendor specific
[RGB smart bulb]# [NEW] Characteristic (Handle 0x0000)
	/org/bluez/hci0/dev_REDACTED/service001b/char001c
	0000a101-1115-1000-0001-617573746f6d
	Vendor specific
[RGB smart bulb]# [NEW] Characteristic (Handle 0x0000)
	/org/bluez/hci0/dev_REDACTED/service001b/char001e
	0000a102-1115-1000-0001-617573746f6d
	Vendor specific
[RGB smart bulb]# [NEW] Descriptor (Handle 0x0000)
	/org/bluez/hci0/dev_REDACTED/service001b/char001e/desc0020
	00002902-0000-1000-8000-00805f9b34fb
	Client Characteristic Configuration
```

## Observations and limits

- BlueZ reported a successful connection and displayed GATT attributes.
- The supplied output contains three primary services, eleven
  characteristics and two descriptors.
- No characteristic values or access flags are included in this output.
- The manufacturer, model, RGB command format and roles of the
  vendor-specific characteristics remain unknown.
- No `ServicesResolved: yes` message is included; this excerpt alone
  does not establish that the attribute listing is complete.

## Next step

Inspect characteristic flags with `attribute-info`, starting with Device
Information and the two vendor-specific characteristics. Read only
characteristics whose flags allow it, and redact any returned serial
number before recording results. Do not guess or send RGB payloads.