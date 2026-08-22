//
//  ContentView.swift
//  NSosyal
//
//  Created by Furkan Durmaz on 22.08.2026.
//

import SwiftUI

struct ContentView: View {
    @AppStorage("nsosyal.authentication.completed") private var hasAuthenticated = false
    @AppStorage("nsosyal.onboarding.completed") private var hasCompletedOnboarding = false
    @StateObject private var store = AppStore()

    private var skipsAuthenticationForDevelopment: Bool {
        ProcessInfo.processInfo.arguments.contains("-skipAuthentication")
            || ProcessInfo.processInfo.arguments.contains("-skipOnboarding")
    }

    private var skipsOnboardingForDevelopment: Bool {
        ProcessInfo.processInfo.arguments.contains("-skipOnboarding")
    }

    var body: some View {
        ZStack {
            if !hasAuthenticated && !skipsAuthenticationForDevelopment {
                AuthenticationView {
                    withAnimation(NSTheme.gentleSpring) {
                        hasAuthenticated = true
                    }
                }
                .transition(.opacity)
            } else if hasCompletedOnboarding || skipsOnboardingForDevelopment {
                MainTabView()
                    .environmentObject(store)
                    .transition(.opacity.combined(with: .scale(scale: 0.985)))
            } else {
                OnboardingView {
                    withAnimation(NSTheme.gentleSpring) {
                        hasCompletedOnboarding = true
                    }
                }
                .environmentObject(store)
                .transition(.opacity)
            }
        }
        .tint(NSTheme.ink)
        .preferredColorScheme(.light)
    }
}
