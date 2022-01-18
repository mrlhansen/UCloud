//DEPS dk.sdu.cloud:k8-resources:0.1.2
package dk.sdu.cloud.k8

bundle {
    name = "file-ucloud"
    version = "2022.1.0-patch.4"

    withAmbassador(null) {
        addSimpleMapping("/ucloud/ucloud/chunked")
        addSimpleMapping("/ucloud/ucloud/files")
        addSimpleMapping("/ucloud/ucloud/shares")
        addSimpleMapping("/ucloud/ucloud/download")
    }
    
    val deployment = withDeployment {
        deployment.spec.replicas = Configuration.retrieve("defaultScale", "Default scale", 1)
        injectSecret("elasticsearch-credentials")
        injectSecret("ucloud-provider-tokens")

        val cephfsVolume = "cephfs"
        serviceContainer.volumeMounts.add(VolumeMount().apply {
            name = cephfsVolume
            mountPath = "/mnt/cephfs"
        })

        volumes.add(Volume().apply {
            name = cephfsVolume
            persistentVolumeClaim = PersistentVolumeClaimVolumeSource().apply {
                claimName = cephfsVolume
            }
        })
    }
    
    withPostgresMigration(deployment)
}