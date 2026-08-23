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
    @StateObject private var authenticationStore = AuthenticationStore()

    private var skipsAuthenticationForDevelopment: Bool {
        ProcessInfo.processInfo.arguments.contains("-skipAuthentication")
            || ProcessInfo.processInfo.arguments.contains("-skipOnboarding")
    }

    private var skipsOnboardingForDevelopment: Bool {
        ProcessInfo.processInfo.arguments.contains("-skipOnboarding")
    }

    var body: some View {
        ZStack {
            if authenticationStore.isRestoringSession && !skipsAuthenticationForDevelopment {
                ProgressView("Oturum kontrol ediliyor…")
                    .tint(NSTheme.blue)
            } else if !authenticationStore.isAuthenticated && !skipsAuthenticationForDevelopment {
                AuthenticationView(authenticationStore: authenticationStore)
                .transition(.opacity)
            } else if hasCompletedOnboarding || skipsOnboardingForDevelopment {
                MainTabView()
                    .environmentObject(store)
                    .environmentObject(authenticationStore)
                    .transition(.opacity.combined(with: .scale(scale: 0.985)))
            } else {
                OnboardingView {
                    withAnimation(NSTheme.gentleSpring) {
                        hasCompletedOnboarding = true
                    }
                }
                .environmentObject(store)
                .environmentObject(authenticationStore)
                .transition(.opacity)
            }
        }
        .tint(NSTheme.ink)
        .preferredColorScheme(.light)
        .task {
            await authenticationStore.restoreSession()
            await store.configure(dataSourceMode: authenticationStore.dataSourceMode)
        }
    }
}
