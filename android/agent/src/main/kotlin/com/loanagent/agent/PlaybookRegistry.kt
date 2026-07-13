package com.loanagent.agent

class PlaybookRegistry {
    private val playbooks = LinkedHashMap<String, Playbook>()

    fun register(baseName: String, playbook: Playbook): PlaybookRegistry {
        playbooks[baseName] = playbook
        return this
    }

    fun get(baseName: String): Playbook? = playbooks[baseName]
}
