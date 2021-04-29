package de.fraunhofer.ccit.examples

class RouteComponent {
    var bundle: String? = null
        private set
    var description: String? = null
        private set

    constructor() {
        /* Bean std c'tor */
    }

    constructor(bundleName: String?, description: String?) {
        bundle = bundleName
        this.description = description
    }
}