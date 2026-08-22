import SwiftUI

enum NSTheme {
    static let canvas = Color(red: 0.985, green: 0.986, blue: 0.978)
    static let surface = Color.white
    static let elevatedSurface = Color(red: 0.955, green: 0.956, blue: 0.946)
    static let ink = Color(red: 0.075, green: 0.08, blue: 0.09)
    static let mutedInk = Color(red: 0.39, green: 0.40, blue: 0.43)
    static let subtleInk = Color(red: 0.59, green: 0.60, blue: 0.63)
    static let border = Color.black.opacity(0.075)
    static let strongBorder = Color.black.opacity(0.12)

    static let cyan = Color(red: 0.08, green: 0.77, blue: 0.88)
    static let blue = Color(red: 0.22, green: 0.47, blue: 0.98)
    static let violet = Color(red: 0.49, green: 0.28, blue: 0.94)
    static let amber = Color(red: 0.97, green: 0.68, blue: 0.12)
    static let green = Color(red: 0.13, green: 0.67, blue: 0.45)
    static let coral = Color(red: 0.95, green: 0.35, blue: 0.35)

    static let horizontalPadding: CGFloat = 18
    static let cornerRadius: CGFloat = 18
    static let largeCornerRadius: CGFloat = 26
    static let controlHeight: CGFloat = 52

    static let brandGradient = LinearGradient(
        colors: [cyan, blue, violet],
        startPoint: .topLeading,
        endPoint: .bottomTrailing
    )

    static let darkGradient = LinearGradient(
        colors: [Color(red: 0.055, green: 0.075, blue: 0.14),
                 Color(red: 0.09, green: 0.12, blue: 0.25)],
        startPoint: .topLeading,
        endPoint: .bottomTrailing
    )

    static let spring = Animation.spring(response: 0.38, dampingFraction: 0.86)
    static let gentleSpring = Animation.spring(response: 0.48, dampingFraction: 0.92)
}

struct SurfaceCardModifier: ViewModifier {
    var radius: CGFloat = NSTheme.cornerRadius
    var hasShadow = false

    func body(content: Content) -> some View {
        content
            .background(NSTheme.surface, in: RoundedRectangle(cornerRadius: radius, style: .continuous))
            .overlay {
                RoundedRectangle(cornerRadius: radius, style: .continuous)
                    .stroke(NSTheme.border, lineWidth: 1)
            }
            .shadow(
                color: hasShadow ? Color.black.opacity(0.055) : .clear,
                radius: 18,
                y: 8
            )
    }
}

extension View {
    func surfaceCard(radius: CGFloat = NSTheme.cornerRadius, shadow: Bool = false) -> some View {
        modifier(SurfaceCardModifier(radius: radius, hasShadow: shadow))
    }

    func pressScale() -> some View {
        buttonStyle(PressScaleButtonStyle())
    }
}

struct PressScaleButtonStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .scaleEffect(configuration.isPressed ? 0.965 : 1)
            .opacity(configuration.isPressed ? 0.88 : 1)
            .animation(.easeOut(duration: 0.14), value: configuration.isPressed)
    }
}

struct PrimaryButtonStyle: ButtonStyle {
    var isEnabled = true

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.system(size: 16, weight: .semibold))
            .foregroundStyle(.white)
            .frame(maxWidth: .infinity)
            .frame(height: NSTheme.controlHeight)
            .background(
                isEnabled ? AnyShapeStyle(NSTheme.ink) : AnyShapeStyle(NSTheme.ink.opacity(0.28)),
                in: Capsule()
            )
            .scaleEffect(configuration.isPressed ? 0.975 : 1)
            .animation(.easeOut(duration: 0.14), value: configuration.isPressed)
    }
}

struct GlassIconButton: View {
    let systemName: String
    var badge: Int? = nil
    var accessibilityLabel: String
    var action: () -> Void

    var body: some View {
        Button(action: action) {
            ZStack(alignment: .topTrailing) {
                Image(systemName: systemName)
                    .font(.system(size: 17, weight: .semibold))
                    .foregroundStyle(NSTheme.ink)
                    .frame(width: 42, height: 42)
                    .background(.regularMaterial, in: Circle())
                    .overlay {
                        Circle().stroke(NSTheme.border, lineWidth: 1)
                    }

                if let badge, badge > 0 {
                    Text("\(min(badge, 9))")
                        .font(.system(size: 9, weight: .bold))
                        .foregroundStyle(.white)
                        .frame(width: 17, height: 17)
                        .background(NSTheme.coral, in: Circle())
                        .offset(x: 2, y: -1)
                }
            }
        }
        .pressScale()
        .accessibilityLabel(accessibilityLabel)
    }
}

struct BrandMark: View {
    var size: CGFloat = 38

    var body: some View {
        ZStack {
            RoundedRectangle(cornerRadius: size * 0.31, style: .continuous)
                .fill(NSTheme.ink)

            Path { path in
                path.move(to: CGPoint(x: size * 0.29, y: size * 0.72))
                path.addLine(to: CGPoint(x: size * 0.29, y: size * 0.28))
                path.addLine(to: CGPoint(x: size * 0.70, y: size * 0.72))
                path.addLine(to: CGPoint(x: size * 0.70, y: size * 0.28))
            }
            .stroke(
                NSTheme.brandGradient,
                style: StrokeStyle(lineWidth: size * 0.105, lineCap: .round, lineJoin: .round)
            )
        }
        .frame(width: size, height: size)
        .accessibilityHidden(true)
    }
}

struct NexiOrb: View {
    var size: CGFloat = 54

    var body: some View {
        ZStack {
            Circle()
                .fill(NSTheme.brandGradient)
                .shadow(color: NSTheme.blue.opacity(0.28), radius: size * 0.22, y: size * 0.08)

            Circle()
                .fill(Color.white.opacity(0.2))
                .frame(width: size * 0.58, height: size * 0.58)
                .offset(x: -size * 0.12, y: -size * 0.14)

            Image(systemName: "sparkles")
                .font(.system(size: size * 0.34, weight: .bold))
                .foregroundStyle(.white)
        }
        .frame(width: size, height: size)
        .accessibilityLabel("Nexi")
    }
}

struct AvatarView: View {
    let initials: String
    var colors: [Color] = [NSTheme.blue, NSTheme.violet]
    var size: CGFloat = 42
    var showsVerified = false

    var body: some View {
        ZStack(alignment: .bottomTrailing) {
            Circle()
                .fill(
                    LinearGradient(
                        colors: colors,
                        startPoint: .topLeading,
                        endPoint: .bottomTrailing
                    )
                )
                .overlay {
                    Text(initials)
                        .font(.system(size: size * 0.31, weight: .bold))
                        .foregroundStyle(.white)
                }
                .frame(width: size, height: size)

            if showsVerified {
                Image(systemName: "checkmark.seal.fill")
                    .font(.system(size: size * 0.29))
                    .symbolRenderingMode(.palette)
                    .foregroundStyle(.white, NSTheme.blue)
                    .background(Color.white, in: Circle())
                    .offset(x: size * 0.04, y: size * 0.04)
            }
        }
        .accessibilityHidden(true)
    }
}
