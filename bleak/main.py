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
FOLLOWUP_WRITES = (
    bytes.fromhex("fe020802"),
    bytes.fromhex("ff08020402041404809800000000000000000000"),
)
THIRD_EXCHANGE_WRITES = (
    bytes.fromhex("fe020802"),
    bytes.fromhex("ff0802040204140488a000000000000000000000"),
)
STREAM_EXCHANGE_WRITES = (
    bytes.fromhex("fe020b02"),
    bytes.fromhex("ff0b020402070207820000008b00000000000000"),
)
PERIODIC_CANDIDATE_EXCHANGES = (
    (
        bytes.fromhex("fe021903"),
        bytes.fromhex("001202040215141502000f800040000000000000"),
    ),
    (
        bytes.fromhex("fe021803"),
        bytes.fromhex("001202040214141402000e1c9631001040400080"),
    ),
)
PERIODIC_CANDIDATE_INTERVAL = 0.37
PERIODIC_CANDIDATE_DURATION = 15.0
CAPTURED_IDLE_SETUP = (
    (
        "0a exchange",
        (
            bytes.fromhex("fe020a02"),
            bytes.fromhex("ff0a0204020602068400008c0000000000000000"),
        ),
        3,
    ),
    (
        "post-0a 08 exchange",
        (
            bytes.fromhex("fe020802"),
            bytes.fromhex("ff08020402040204959b00000000000000000000"),
        ),
        3,
    ),
    (
        "2c exchange",
        (
            bytes.fromhex("fe022c04"),
            bytes.fromhex("0012020402281428900701f2f4f4f2fe08001e2a"),
            bytes.fromhex("01123c4c5a669088a6c2e404224e98b0ce1a2c7c"),
            bytes.fromhex("ff088ad620980200000000000000000000000000"),
        ),
        2,
    ),
    (
        "post-2c 19 exchange",
        (
            bytes.fromhex("fe021903"),
            bytes.fromhex("0012020402151415020e00000000000000000000"),
            bytes.fromhex("ff070000001001004a0000000000000000000000"),
        ),
        2,
    ),
    (
        "17 exchange",
        (
            bytes.fromhex("fe021703"),
            bytes.fromhex("0012020402131413020c00000000000000000000"),
            bytes.fromhex("ff0500800100b600000000000000000000000000"),
        ),
        2,
    ),
    (
        "post-17 19 exchange",
        (
            bytes.fromhex("fe021903"),
            bytes.fromhex("001202040215141502000f001000c01c4c0000e0"),
            bytes.fromhex("ff070000001000086a0000000000000000000000"),
        ),
        3,
    ),
    (
        "post-17 18 exchange",
        (
            bytes.fromhex("fe021803"),
            bytes.fromhex("001202040214141402000e1c9631001040400080"),
            bytes.fromhex("ff0600000081600c000000000000000000000000"),
        ),
        4,
    ),
    (
        "first 11 exchange",
        (
            bytes.fromhex("fe021102"),
            bytes.fromhex("ff110204020d140d020500000000085802008a00"),
        ),
        2,
    ),
    (
        "second 11 exchange",
        (
            bytes.fromhex("fe021102"),
            bytes.fromhex("ff110204020d140d020500000000085802008a00"),
        ),
        2,
    ),
    (
        "post-11 19 exchange",
        (
            bytes.fromhex("fe021903"),
            bytes.fromhex("0012020402151415020e00000000000000000000"),
            bytes.fromhex("ff070000001001004a0000000000000000000000"),
        ),
        2,
    ),
    (
        "first 10 exchange",
        (
            bytes.fromhex("fe021002"),
            bytes.fromhex("ff100204020c140c020400000002a81600e60000"),
        ),
        2,
    ),
    (
        "post-10 19 exchange",
        (
            bytes.fromhex("fe021903"),
            bytes.fromhex("001202040215141502000f800040000000000000"),
            bytes.fromhex("ff070000001080119b0000000000000000000000"),
        ),
        3,
    ),
    (
        "second 10 exchange",
        (
            bytes.fromhex("fe021002"),
            bytes.fromhex("ff100204020c140c020500000000100100380000"),
        ),
        2,
    ),
    (
        "post-10 18 exchange",
        (
            bytes.fromhex("fe021803"),
            bytes.fromhex("001202040214141402000e1c9631001040400080"),
            bytes.fromhex("ff0600000081600c000000000000000000000000"),
        ),
        4,
    ),
)


def handle_notification(sender, data):
    timestamp = datetime.now().astimezone().isoformat(timespec="milliseconds")
    print(f"[{timestamp}] {sender.uuid}: {data.hex(' ')}", flush=True)


async def wait_for_notification_burst(notification_queue, label, expected_count=3):
    received = 0
    try:
        async with asyncio.timeout(3.0):
            for _ in range(expected_count):
                await notification_queue.get()
                received += 1
    except TimeoutError:
        print(
            f"Only received {received} of {expected_count} notifications for {label} within 3 seconds."
        )
        return False

    print(f"Received {expected_count} notifications for {label}.")
    return True


async def send_captured_exchange(
    client,
    characteristic,
    notification_queue,
    label,
    writes,
    expected_count,
):
    discarded = 0
    while not notification_queue.empty():
        notification_queue.get_nowait()
        discarded += 1
    if discarded:
        print(f"Discarded {discarded} queued notifications before {label}.")

    for payload in writes:
        print(f"Sending {label} payload: {payload.hex(' ')}")
        await client.write_gatt_char(characteristic, payload, response=True)

    return await wait_for_notification_burst(
        notification_queue,
        label,
        expected_count,
    )


async def run_periodic_candidate_test(client, characteristic):
    loop = asyncio.get_running_loop()
    stop_at = loop.time() + PERIODIC_CANDIDATE_DURATION
    next_exchange_at = loop.time()
    exchange_index = 0

    while client.is_connected and next_exchange_at < stop_at:
        delay = next_exchange_at - loop.time()
        if delay > 0:
            await asyncio.sleep(delay)
        if not client.is_connected:
            break

        exchange = PERIODIC_CANDIDATE_EXCHANGES[
            exchange_index % len(PERIODIC_CANDIDATE_EXCHANGES)
        ]
        for payload in exchange:
            print(f"Sending periodic candidate payload: {payload.hex(' ')}")
            await client.write_gatt_char(
                characteristic,
                payload,
                response=True,
            )

        exchange_index += 1
        next_exchange_at += PERIODIC_CANDIDATE_INTERVAL
        if next_exchange_at < loop.time():
            next_exchange_at = loop.time() + PERIODIC_CANDIDATE_INTERVAL

    print(f"Completed {exchange_index} periodic candidate exchanges.")


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

        notification_queue = asyncio.Queue()

        def on_notification(sender, data):
            handle_notification(sender, data)
            notification_queue.put_nowait(bytes(data))

        await client.start_notify(characteristic, on_notification)
        print(f"Listening for notifications on {characteristic.uuid}. Press Ctrl+C to stop.")

        answer = await asyncio.to_thread(
            input,
            "Send the captured initialization, follow-up, and 0b exchanges? [y/N]: ",
        )
        if answer.strip().lower() == "y":
            for payload in INITIALIZATION_WRITES:
                print(f"Sending initialization payload: {payload.hex(' ')}")
                await client.write_gatt_char(
                    command_characteristic,
                    payload,
                    response=True,
                )

            if await wait_for_notification_burst(notification_queue, "initialization"):
                for payload in FOLLOWUP_WRITES:
                    print(f"Sending one follow-up payload: {payload.hex(' ')}")
                    await client.write_gatt_char(
                        command_characteristic,
                        payload,
                        response=True,
                    )
                if await wait_for_notification_burst(notification_queue, "follow-up"):
                    for payload in THIRD_EXCHANGE_WRITES:
                        print(f"Sending third-exchange payload: {payload.hex(' ')}")
                        await client.write_gatt_char(
                            command_characteristic,
                            payload,
                            response=True,
                        )
                    if await wait_for_notification_burst(
                        notification_queue,
                        "third exchange",
                        expected_count=2,
                    ) and client.is_connected:
                        for payload in STREAM_EXCHANGE_WRITES:
                            print(f"Sending 0b-exchange payload: {payload.hex(' ')}")
                            await client.write_gatt_char(
                                command_characteristic,
                                payload,
                                response=True,
                            )
                        await wait_for_notification_burst(
                            notification_queue,
                            "0b exchange",
                        )
                        if client.is_connected:
                            setup_answer = await asyncio.to_thread(
                                input,
                                "Send idle setup (0a/2c/17/11/10)? [y/N]: ",
                            )
                            periodic_answer = await asyncio.to_thread(
                                input,
                                "Run 18/19 test for 15s? [y/N]: ",
                            )
                            run_periodic_test = (
                                periodic_answer.strip().lower() == "y"
                            )

                            if setup_answer.strip().lower() == "y":
                                for setup_exchange in CAPTURED_IDLE_SETUP:
                                    (
                                        label,
                                        writes,
                                        expected_count,
                                    ) = setup_exchange
                                    if not await send_captured_exchange(
                                        client,
                                        command_characteristic,
                                        notification_queue,
                                        label,
                                        writes,
                                        expected_count,
                                    ):
                                        run_periodic_test = False
                                        break

                            if run_periodic_test and client.is_connected:
                                await run_periodic_candidate_test(
                                    client,
                                    command_characteristic,
                                )
        else:
            print("Initialization and follow-up writes skipped; listening passively.")

        while client.is_connected:
            await asyncio.sleep(1)
        print("BLE connection disconnected.")

asyncio.run(main())