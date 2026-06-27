import SwiftUI
import HealthKit

struct ContentView: View {
    @ObservedObject var syncManager: SleepSyncManager
    @StateObject private var discoveryManager = BridgeDiscoveryManager()
    @State private var showSettings = false
    @State private var serverIP: String = UserDefaults.standard.string(forKey: "serverIP") ?? ""
    @State private var port: String = UserDefaults.standard.string(forKey: "port") ?? "8787"

    var body: some View {
        NavigationView {
            VStack(spacing: 20) {
                Image(systemName: "moon.zzz.fill")
                    .font(.system(size: 60))
                    .foregroundColor(.blue)

                Text("GalaxyBridge")
                    .font(.title)
                    .fontWeight(.bold)

                Text("Samsung Sleep → Apple Health")
                    .font(.subheadline)
                    .foregroundColor(.secondary)

                Spacer()

                VStack(alignment: .leading, spacing: 8) {
                    HStack {
                        Text("Android Server IP")
                            .font(.headline)
                        Spacer()
                        Button("保存") {
                            saveIP()
                        }
                        .font(.subheadline)
                    }

                    HStack {
                        TextField("192.168.x.x", text: $serverIP)
                            .keyboardType(.decimalPad)
                            .textFieldStyle(RoundedBorderTextFieldStyle())
                            .autocapitalization(.none)

                        Text(":")
                            .font(.title2)

                        TextField("8787", text: $port)
                            .keyboardType(.numberPad)
                            .textFieldStyle(RoundedBorderTextFieldStyle())
                            .frame(width: 70)
                    }

                    Button(action: {
                        discoverBridge()
                    }) {
                        HStack {
                            Image(systemName: "antenna.radiowaves.left.and.right")
                            Text(discoveryManager.isScanning ? "Discovering..." : "Auto Discover")
                        }
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 10)
                    }
                    .buttonStyle(.borderedProminent)
                    .disabled(discoveryManager.isScanning)

                    Text(discoveryManager.status)
                        .font(.footnote)
                        .foregroundColor(.secondary)
                }
                .padding(.horizontal)

                VStack(alignment: .leading, spacing: 8) {
                    Text("Status")
                        .font(.headline)

                    ScrollView {
                        Text(syncManager.status)
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .padding(8)
                    }
                    .frame(height: 120)
                    .background(Color(.systemGray6))
                    .cornerRadius(8)
                }
                .padding(.horizontal)

                Spacer()

                VStack(spacing: 12) {
                    Button(action: {
                        Task {
                            await syncManager.requestHealthKitPermissions()
                        }
                    }) {
                        HStack {
                            Image(systemName: "heart.fill")
                            Text("Grant HealthKit Permissions")
                        }
                        .frame(maxWidth: .infinity)
                        .padding()
                        .background(Color.green)
                        .foregroundColor(.white)
                        .cornerRadius(10)
                    }

                    Button(action: {
                        saveIP()
                        Task {
                            await syncManager.fetchAndSyncSleep()
                        }
                    }) {
                        HStack {
                            Image(systemName: "arrow.triangle.2.circlepath")
                            if syncManager.isLoading {
                                ProgressView()
                                    .progressViewStyle(CircularProgressViewStyle(tint: .white))
                            } else {
                                Text("Sync Now")
                            }
                        }
                        .frame(maxWidth: .infinity)
                        .padding()
                        .background(syncManager.isLoading ? Color.gray : Color.blue)
                        .foregroundColor(.white)
                        .cornerRadius(10)
                    }
                    .disabled(syncManager.isLoading || serverIP.isEmpty)
                }
                .padding(.horizontal)
                .padding(.bottom, 30)
            }
            .navigationTitle("")
            .navigationBarHidden(true)
            .onAppear {
                if serverIP.isEmpty {
                    discoverBridge()
                }
            }
        }
    }

    private func saveIP() {
        UserDefaults.standard.set(serverIP, forKey: "serverIP")
        UserDefaults.standard.set(port, forKey: "port")
        syncManager.updateServer(ip: serverIP, port: port)
    }

    private func discoverBridge() {
        discoveryManager.scan { endpoint in
            serverIP = endpoint.host
            port = String(endpoint.port)
            saveIP()
        }
    }
}

#Preview {
    ContentView(syncManager: SleepSyncManager())
}
