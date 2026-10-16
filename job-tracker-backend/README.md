# Online Job Application Tracker - Backend

## Stack
Java 25 (LTS), Spring Boot, Spring Security, JWT, PostgreSQL, Razorpay.

## 1. Database
Create a PostgreSQL database:
job_tracker_db

## 2. Configuration
Edit:
src/main/resources/application.properties

Set:
- PostgreSQL password
- JWT secret
- Razorpay test key id
- Razorpay test key secret

Never put the Razorpay secret in the React frontend.

## 3. Run
mvn spring-boot:run

Backend:
http://localhost:8080

Swagger:
http://localhost:8080/swagger-ui.html

## 4. Authentication
POST /api/auth/signup
POST /api/auth/signin

Use:
Authorization: Bearer <JWT>

## 5. Main APIs
Applications:
GET/POST /api/applications
GET/PUT/DELETE /api/applications/{id}

Interviews:
GET/POST /api/interviews
GET/PUT/DELETE /api/interviews/{id}

Reminders:
GET/POST /api/reminders
GET/PUT/DELETE /api/reminders/{id}

Dashboard:
GET /api/dashboard/statistics

Users:
GET /api/users/profile
PUT /api/users/profile

Payments:
POST /api/payments/create-order
POST /api/payments/verify
GET /api/payments/my-payments

Razorpay amount is stored in paise. 49900 = Rs. 499.


## Email Notification Feature

This project now includes Gmail SMTP email notifications for job follow-up reminders.

See `EMAIL_NOTIFICATION_SETUP.md` for complete setup and testing instructions.

Main backend components:
- `EmailNotificationService`
- `ReminderNotificationScheduler`
- `NotificationController`

The reminder table records `notification_sent` and `notification_sent_at` to prevent duplicate emails.
