import asyncio
from bleak import BleakScanner, BleakClient

TARGET_NAME = "I_RW" 

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

asyncio.run(main())