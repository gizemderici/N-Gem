//
//  ContentView.swift
//  NSosyal
//
//  Created by Furkan Durmaz on 22.08.2026.
//

import SwiftUI

struct ContentView: View {
    @AppStorage("nsosyal.onboarding.completed") private var hasCompletedOnboarding = false
    @StateObject private var store = AppStore()

    private var skipsOnboardingForDevelopment: Bool {
        ProcessInfo.processInfo.arguments.contains("-skipOnboarding")
    }

    var body: some View {
        ZStack {
            if hasCompletedOnboarding || skipsOnboardingForDevelopment {
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
