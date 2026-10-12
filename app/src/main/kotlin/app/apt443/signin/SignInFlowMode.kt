package app.apt443.signin

enum class SignInFlowMode {
    /** Owner uploads a site package and tests the console connection. */
    SiteSetup,

    /** Resident picks their name and confirms email. */
    TenantSignIn,
}
