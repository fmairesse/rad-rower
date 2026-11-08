import asyncio
from datetime import datetime
from bleak import BleakScanner, BleakClient

TARGET_NAME = "I_RW"
NOTIFICATION_CHAR_UUID = "00001535-1412-efde-1523-785feabcd123"
COMMAND_CHAR_UUID = "00001534-1412-efde-1523-785feabcd123"
INITIALIZATION_WRITES = (
    bytes.fromhex("fe020802"),
    bytes.fromhex("ff08020402040204818700000000000000000000"),
)


def handle_notification(sender, data):
    timestamp = datetime.now().astimezone().isoformat(timespec="milliseconds")
    print(f"[{timestamp}] {sender.uuid}: {data.hex(' ')}", flush=True)

async def main():
    print("Searching rower...")
    device = await BleakScanner.find_device_by_name(TARGET_NAME, timeout=10.0)
    
    if not device:
        print("Rower not found. Check it's on and not on iFit.")
        return

    print(f"Found rower {device.address}")

    async with BleakClient(device.address) as client:
        print(f"Connected : {client.is_connected}")

        # Exploring GATT services and chars
        for service in client.services:
            print(f"\n[Service] {service.uuid} - {service.description}")
            for char in service.characteristics:
                print(f"  [Characteristic] {char.uuid} | Props: {char.properties}")

        characteristic = client.services.get_characteristic(NOTIFICATION_CHAR_UUID)
        if characteristic is None:
            raise RuntimeError(f"Notification characteristic not found: {NOTIFICATION_CHAR_UUID}")
        if "notify" not in characteristic.properties:
            raise RuntimeError(f"Characteristic does not support notifications: {NOTIFICATION_CHAR_UUID}")

        command_characteristic = client.services.get_characteristic(COMMAND_CHAR_UUID)
        if command_characteristic is None:
            raise RuntimeError(f"Command characteristic not found: {COMMAND_CHAR_UUID}")
        if "write" not in command_characteristic.properties:
            raise RuntimeError(f"Characteristic does not support writes: {COMMAND_CHAR_UUID}")

        await client.start_notify(characteristic, handle_notification)
        print(f"Listening for notifications on {characteristic.uuid}. Press Ctrl+C to stop.")

        answer = await asyncio.to_thread(
            input,
            "Send the two captured initialization writes to the rower? [y/N]: ",
        )
        if answer.strip().lower() == "y":
            for payload in INITIALIZATION_WRITES:
                print(f"Sending initialization payload: {payload.hex(' ')}")
                await client.write_gatt_char(
                    command_characteristic,
                    payload,
                    response=True,
                )
        else:
            print("Initialization writes skipped; listening passively.")

        while client.is_connected:
            await asyncio.sleep(1)

asyncio.run(main())