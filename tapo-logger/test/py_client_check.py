# Cross-check: python-kasa's real KLAP client against the mock (validates the mock itself)
import asyncio, sys
from kasa.transports.klaptransport import KlapTransportV2
from kasa.deviceconfig import DeviceConfig
from kasa.credentials import Credentials
async def main():
    t = KlapTransportV2(config=DeviceConfig(f"127.0.0.1", port_override=int(sys.argv[1]),
                        credentials=Credentials(sys.argv[2], sys.argv[3])))
    r = await t.send('{"method":"get_energy_usage"}'); print("python-kasa client OK:", r)
    await t.close()
asyncio.run(main())
