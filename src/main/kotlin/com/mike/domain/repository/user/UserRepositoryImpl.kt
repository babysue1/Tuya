package com.mike.domain.repository.user

import com.mike.domain.model.user.*
import com.mike.domain.repository.auth.AuthRepository
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.LocalDateTime

class UserRepositoryImpl(
    private val authRepository: AuthRepository
) : UserRepository {

    override fun findByEmail(email: String): Profile? = transaction {
        try {
            (Users innerJoin Profiles)
                .selectAll().where { Users.email eq email }
                .singleOrNull()
                ?.let { resultRow ->
                    mapToProfile(resultRow)
                }
        } catch (e: Exception) {
            println("Error finding user by email: ${e.message}")
            null
        }
    }

    override fun findById(userId: Int): User? = transaction {
        try {
            (Users innerJoin Profiles)
                .selectAll().where { Users.userId eq userId }
                .singleOrNull()
                ?.let { resultRow ->
                    mapToUser(resultRow)
                }
        } catch (e: Exception) {
            println("Error finding user by ID: ${e.message}")
            null
        }
    }

    override fun findUserProfile(userId: Int): Profile? = transaction {
        try {
            (Profiles innerJoin Users)
                .selectAll().where { Profiles.userId eq userId }
                .singleOrNull()
                ?.let { resultRow ->
                    mapToProfile(resultRow)
                }
        } catch (e: Exception) {
            println("Error finding user profile: ${e.message}")
            null
        }
    }

    override fun findUserRole(userId: Int): String? = transaction {
        try {
            Users.selectAll().where { Users.userId eq userId }
                .singleOrNull()?.get(Users.role)
        } catch (e: Exception) {
            println("Error finding user role: ${e.message}")
            null
        }
    }

    override fun getAllUsers(): List<Profile> = transaction {
        try {
            (Profiles innerJoin Users)
                .selectAll()
                .map { resultRow ->
                    mapToProfile(resultRow)
                }
        } catch (e: Exception) {
            println("Error retrieving all users: ${e.message}")
            emptyList()
        }
    }

    override fun createUser(user: RegisterRequest): Pair<Boolean, String?> = transaction {
        try {
            // Check for duplicate email
            val existingUser = Users.selectAll().where { Users.email eq user.email }.singleOrNull()
            if (existingUser != null) {
                return@transaction Pair(false, "User with email ${user.email} already exists")
            }
            // Check for duplicate phone number if provided
            if (!user.phoneNumber.isNullOrBlank()) {
                // Remove all spaces from the phone number
                val cleanedPhone = user.phoneNumber.replace(" ", "")
                // Validate phone number format: +254XXXXXXXXX or 0XXXXXXXXX
                val phoneRegex = "^(\\+254\\d{9}|0\\d{9})$".toRegex()
                if (!cleanedPhone.matches(phoneRegex)) {
                    return@transaction Pair(false, "Phone number must be in the format +254XXXXXXXXX or 0XXXXXXXXX")
                }
                val existingPhone = Profiles.selectAll().where { Profiles.phoneNumber eq cleanedPhone }.singleOrNull()
                if (existingPhone != null) {
                    return@transaction Pair(false, "User with phone number ${cleanedPhone} already exists")
                }
            }
            // Check for valid email format
            val emailRegex = "^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+$".toRegex()
            if (!user.email.matches(emailRegex)) {
                return@transaction Pair(false, "Invalid email format")
            }
            if (user.email.isBlank()) {
                return@transaction Pair(false, "Email is required")
            }
            if (user.password.isBlank()) {
                return@transaction Pair(false, "Password is required")
            }
            val now = LocalDateTime.now()
            val userId = Users.insert {
                it[email] = user.email
                it[passwordHash] = authRepository.hashPassword(user.password)
                it[role] = "USER"
            } get Users.userId
            Profiles.insert {
                it[Profiles.userId] = userId
                it[firstName] = user.firstName
                it[lastName] = user.lastName
                it[phoneNumber] = user.phoneNumber
                it[email] = user.email
                it[createdAt] = now
                it[updatedAt] = now
            }
            Pair(true, null)
        } catch (e: Exception) {
            Pair(false, "Failed to create user: ${e.message}")
        }
    }

    override fun updateUser(updatedUser: ProfileUpdateRequest): Pair<Boolean, String?> = transaction {
        try {
            Users.selectAll().where { Users.userId eq updatedUser.userId }
                .singleOrNull() ?: return@transaction Pair(false, "User not found")
            // Clean and validate a phone number if provided
            val cleanedPhone: String? = updatedUser.phoneNumber?.replace(" ", "")
            if (!cleanedPhone.isNullOrBlank()) {
                val phoneRegex = "^(\\+254\\d{9}|0\\d{9})$".toRegex()
                if (!cleanedPhone.matches(phoneRegex)) {
                    return@transaction Pair(false, "Phone number must be in the format +254XXXXXXXXX or 0XXXXXXXXX")
                }
                // Check for duplicate phone number (excluding current user)
                val existingPhone = Profiles.selectAll()
                    .where { (Profiles.phoneNumber eq cleanedPhone) and (Profiles.userId neq updatedUser.userId) }
                    .singleOrNull()
                if (existingPhone != null) {
                    return@transaction Pair(false, "User with phone number $cleanedPhone already exists")
                }
            }
            // Validate email if provided (and not blank)
            if (!updatedUser.email.isNullOrBlank()) {
                val emailRegex = "^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+$".toRegex()
                if (!updatedUser.email.matches(emailRegex)) {
                    return@transaction Pair(false, "Invalid email format")
                }
                // Check for duplicate email (excluding current user)
                val existingEmail = Users.selectAll()
                    .where { (Users.email eq updatedUser.email) and (Users.userId neq updatedUser.userId) }
                    .singleOrNull()
                if (existingEmail != null) {
                    return@transaction Pair(false, "User with email ${updatedUser.email} already exists")
                }
            }
            Profiles.update({ Profiles.userId eq updatedUser.userId }) {
                it[firstName] = updatedUser.firstName
                it[email] = updatedUser.email ?: updatedUser.email// Keep existing email if not provided
                it[lastName] = updatedUser.lastName
                it[phoneNumber] = cleanedPhone
                it[updatedAt] = LocalDateTime.now()
            }
            Pair(true, null)
        } catch (e: Exception) {
            Pair(false, "Failed to update user: ${e.message}")
        }
    }

    override fun deleteUser(userId: Int): Pair<Boolean, String?> = transaction {
        try {
            Users.selectAll().where { Users.userId eq userId }
                .singleOrNull() ?: return@transaction Pair(false, "User not found")
            ProfilePictures.deleteWhere { ProfilePictures.userId eq userId }
            Profiles.deleteWhere { Profiles.userId eq userId }
            Users.deleteWhere { Users.userId eq userId }
            Pair(true, null)
        } catch (e: Exception) {
            Pair(false, "Failed to delete user: ${e.message}")
        }
    }

    override fun uploadProfilePicture(userId: Int, filename: String, contentType: String, imageData: ByteArray): Pair<Boolean, String?> = transaction {
        try {
            Users.selectAll().where { Users.userId eq userId }
                .singleOrNull() ?: return@transaction Pair(false, "User not found")
            val now = LocalDateTime.now()
            val existingPicture = ProfilePictures.selectAll().where { ProfilePictures.userId eq userId }.singleOrNull()
            if (existingPicture != null) {
                ProfilePictures.update({ ProfilePictures.userId eq userId }) {
                    it[ProfilePictures.filename] = filename
                    it[ProfilePictures.contentType] = contentType
                    it[ProfilePictures.data] = imageData
                    it[updatedAt] = now
                }
            } else {
                ProfilePictures.insert {
                    it[ProfilePictures.userId] = userId
                    it[ProfilePictures.filename] = filename
                    it[ProfilePictures.contentType] = contentType
                    it[ProfilePictures.data] = imageData
                    it[createdAt] = now
                    it[updatedAt] = now
                }
            }
            Pair(true, null)
        } catch (e: Exception) {
            Pair(false, "Failed to upload profile picture: ${e.message}")
        }
    }

    override fun getProfilePicture(userId: Int): ProfilePicture? = transaction {
        try {
            ProfilePictures.selectAll().where { ProfilePictures.userId eq userId }
                .singleOrNull()
                ?.let { resultRow ->
                    ProfilePicture(
                        userId = resultRow[ProfilePictures.userId],
                        filename = resultRow[ProfilePictures.filename],
                        contentType = resultRow[ProfilePictures.contentType],
                        data = resultRow[ProfilePictures.data],
                        createdAt = resultRow[ProfilePictures.createdAt],
                        updatedAt = resultRow[ProfilePictures.updatedAt]
                    )
                }
        } catch (e: Exception) {
            println("Error retrieving profile picture: ${e.message}")
            null
        }
    }

    override fun deleteProfilePicture(userId: Int): Pair<Boolean, String?> {
        return transaction {
            try {
                val existingPicture = ProfilePictures.selectAll().where { ProfilePictures.userId eq userId }.singleOrNull()
                if (existingPicture == null) {
                    return@transaction Pair(false, "Profile picture not found for user ID $userId")
                }
                ProfilePictures.deleteWhere { ProfilePictures.userId eq userId }
                Pair(true, null)
            } catch (e: Exception) {
                Pair(false, "Failed to delete profile picture: ${e.message}")
            }
        }
    }

    private fun mapToUser(row: ResultRow): User {
        return User(
            id = row[Users.userId],
            email = row[Users.email],
            passwordHash = row[Users.passwordHash],
            role = row[Users.role],
            active = true, // Assuming users are active by default
        )
    }

    private val mapToProfile: (row: ResultRow) -> Profile = { row ->
        Profile(
            userId = row[Users.userId],
            email = row[Users.email],
            firstName = row[Profiles.firstName],
            lastName = row[Profiles.lastName],
            phoneNumber = row[Profiles.phoneNumber],
            userRole = row[Users.role],
            createdAt = row[Profiles.createdAt],
            updatedAt = row[Profiles.updatedAt],
            // Generate a URL to access the profile picture via API endpoint
            profilePictureUrl = if (ProfilePictures.selectAll().where { ProfilePictures.userId eq row[Users.userId] }.count() > 0) {
                "/users/${row[Users.userId]}/profile-picture"
            } else {
                null
            }
        )
    }
}
