package deploy

class Ansible {
    ArrayList<Host> hosts
    Host host
    String credentialId
    String inventory
    String inventoryInline
    ArrayList playbooks
}