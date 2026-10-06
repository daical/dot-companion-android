plugins { kotlin("jvm") }

kotlin { jvmToolchain(17) }

dependencies {
    implementation("com.nimbusds:nimbus-jose-jwt:10.10")
    testImplementation("junit:junit:4.13.2")
}

tasks.test { useJUnit() }
