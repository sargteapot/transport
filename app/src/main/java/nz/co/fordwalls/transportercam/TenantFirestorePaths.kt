package nz.co.fordwalls.transportercam

import com.google.firebase.firestore.CollectionReference
import com.google.firebase.firestore.FirebaseFirestore

class TenantFirestorePaths(private val db: FirebaseFirestore) {
    fun collection(companyId: String, name: String): CollectionReference =
        db.collection(collectionPath(companyId, name))

    fun companies(): CollectionReference = db.collection("companies")
    fun jobs(companyId: String) = collection(companyId, "jobs")
    fun fleets(companyId: String) = collection(companyId, "fleets")
    fun drivers(companyId: String) = collection(companyId, "drivers")
    fun prestarts(companyId: String, fleet: String) =
        fleets(companyId).document(fleet).collection("prestarts")

    companion object {
        const val FORDWALLS_ID = "fordwalls"
        fun collectionPath(companyId: String, name: String): String =
            if (companyId == FORDWALLS_ID) name else "companies/$companyId/$name"
    }
}
