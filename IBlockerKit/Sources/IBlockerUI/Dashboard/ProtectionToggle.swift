#if os(iOS)
import SwiftUI

/// The hero on/off control: a large glass shield that starts/stops the VPN.
public struct ProtectionToggle: View {
    @Environment(TunnelController.self) private var tunnel
    @State private var isBusy = false

    public init() {}

    public var body: some View {
        Button {
            guard !isBusy else { return }
            isBusy = true
            Task {
                await tunnel.toggle()
                isBusy = false
            }
        } label: {
            ZStack {
                Circle()
                    .fill(ringGradient)
                    .frame(width: 176, height: 176)
                    .opacity(0.22)

                Circle()
                    .strokeBorder(ringGradient, lineWidth: 5)
                    .frame(width: 176, height: 176)

                VStack(spacing: 8) {
                    Image(systemName: tunnel.isOn ? "shield.checkered" : "shield.slash")
                        .font(.system(size: 52, weight: .semibold))
                        .contentTransition(.symbolEffect(.replace))
                    Text(tunnel.isOn ? "ON" : "OFF")
                        .font(.system(.title3, design: .rounded, weight: .heavy))
                        .tracking(2)
                }
                .foregroundStyle(tunnel.isOn ? Color.green : Color.secondary)

                if isBusy || tunnel.state == .connecting {
                    ProgressView()
                        .controlSize(.large)
                        .offset(y: 64)
                }
            }
        }
        .buttonStyle(.plain)
        .glassEffect(.regular.interactive(), in: .circle)
        .sensoryFeedback(.impact(weight: .medium), trigger: tunnel.isOn)
        .accessibilityLabel(tunnel.isOn ? "Turn protection off" : "Turn protection on")
        .animation(.snappy, value: tunnel.isOn)
    }

    private var ringGradient: LinearGradient {
        LinearGradient(
            colors: tunnel.isOn ? [.green, .teal] : [.gray.opacity(0.5), .gray.opacity(0.3)],
            startPoint: .topLeading,
            endPoint: .bottomTrailing
        )
    }
}
#endif
