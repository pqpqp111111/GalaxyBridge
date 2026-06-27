import CoreBluetooth
import Combine
import Foundation

struct BridgeEndpoint: Decodable {
    let version: Int
    let name: String?
    let host: String
    let port: Int
    let path: String?
    let health: String?
    let tokenHeader: String?
}

final class BridgeDiscoveryManager: NSObject, ObservableObject {
    @Published var isScanning = false
    @Published var status = "BLE discovery idle"
    @Published var endpoint: BridgeEndpoint?

    private let serviceUUID = CBUUID(string: "8E4B7F20-0F74-4DB7-9C71-FB7A2D22C001")
    private let endpointCharacteristicUUID = CBUUID(string: "8E4B7F21-0F74-4DB7-9C71-FB7A2D22C001")
    private var centralManager: CBCentralManager!
    private var targetPeripheral: CBPeripheral?
    private var onFound: ((BridgeEndpoint) -> Void)?

    override init() {
        super.init()
        centralManager = CBCentralManager(delegate: self, queue: nil)
    }

    func scan(onFound: @escaping (BridgeEndpoint) -> Void) {
        self.onFound = onFound

        guard centralManager.state == .poweredOn else {
            status = "Waiting for Bluetooth..."
            return
        }

        startScan()
    }

    func stop() {
        centralManager.stopScan()
        if let targetPeripheral {
            centralManager.cancelPeripheralConnection(targetPeripheral)
        }
        targetPeripheral = nil
        isScanning = false
    }

    private func startScan() {
        guard !isScanning else { return }
        endpoint = nil
        targetPeripheral = nil
        isScanning = true
        status = "Scanning for GalaxyBridge..."
        centralManager.scanForPeripherals(
            withServices: [serviceUUID],
            options: [CBCentralManagerScanOptionAllowDuplicatesKey: false]
        )

        DispatchQueue.main.asyncAfter(deadline: .now() + 12) { [weak self] in
            guard let self, self.isScanning, self.endpoint == nil else { return }
            self.stop()
            self.status = "BLE discovery timed out"
        }
    }

    private func finish(with endpoint: BridgeEndpoint) {
        self.endpoint = endpoint
        status = "Found \(endpoint.host):\(endpoint.port)"
        onFound?(endpoint)
        stop()
    }
}

extension BridgeDiscoveryManager: CBCentralManagerDelegate {
    func centralManagerDidUpdateState(_ central: CBCentralManager) {
        switch central.state {
        case .poweredOn:
            status = "Bluetooth ready"
            if onFound != nil {
                startScan()
            }
        case .unauthorized:
            status = "Bluetooth permission denied"
        case .poweredOff:
            status = "Bluetooth is off"
        case .unsupported:
            status = "Bluetooth unsupported"
        default:
            status = "Bluetooth unavailable"
        }
    }

    func centralManager(
        _ central: CBCentralManager,
        didDiscover peripheral: CBPeripheral,
        advertisementData: [String: Any],
        rssi RSSI: NSNumber
    ) {
        status = "Connecting to GalaxyBridge..."
        targetPeripheral = peripheral
        peripheral.delegate = self
        central.stopScan()
        central.connect(peripheral)
    }

    func centralManager(_ central: CBCentralManager, didConnect peripheral: CBPeripheral) {
        status = "Reading bridge endpoint..."
        peripheral.discoverServices([serviceUUID])
    }

    func centralManager(_ central: CBCentralManager, didFailToConnect peripheral: CBPeripheral, error: Error?) {
        status = "BLE connect failed: \(error?.localizedDescription ?? "unknown error")"
        stop()
    }
}

extension BridgeDiscoveryManager: CBPeripheralDelegate {
    func peripheral(_ peripheral: CBPeripheral, didDiscoverServices error: Error?) {
        if let error {
            status = "BLE service error: \(error.localizedDescription)"
            stop()
            return
        }

        guard let service = peripheral.services?.first(where: { $0.uuid == serviceUUID }) else {
            status = "GalaxyBridge BLE service not found"
            stop()
            return
        }

        peripheral.discoverCharacteristics([endpointCharacteristicUUID], for: service)
    }

    func peripheral(_ peripheral: CBPeripheral, didDiscoverCharacteristicsFor service: CBService, error: Error?) {
        if let error {
            status = "BLE characteristic error: \(error.localizedDescription)"
            stop()
            return
        }

        guard let characteristic = service.characteristics?.first(where: { $0.uuid == endpointCharacteristicUUID }) else {
            status = "GalaxyBridge endpoint characteristic not found"
            stop()
            return
        }

        peripheral.readValue(for: characteristic)
    }

    func peripheral(_ peripheral: CBPeripheral, didUpdateValueFor characteristic: CBCharacteristic, error: Error?) {
        if let error {
            status = "BLE read error: \(error.localizedDescription)"
            stop()
            return
        }

        guard let data = characteristic.value else {
            status = "BLE endpoint was empty"
            stop()
            return
        }

        do {
            let endpoint = try JSONDecoder().decode(BridgeEndpoint.self, from: data)
            guard !endpoint.host.isEmpty else {
                status = "BLE endpoint missing host"
                stop()
                return
            }
            finish(with: endpoint)
        } catch {
            status = "BLE endpoint decode failed: \(error.localizedDescription)"
            stop()
        }
    }
}
