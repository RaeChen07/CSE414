 DROP TABLE IF EXISTS Reservations;
 DROP TABLE IF EXISTS Users;
 DROP TABLE IF EXISTS Itineraries;
   CREATE TABLE Users (
 username VARCHAR(20) PRIMARY KEY,
 password_salt VARBINARY(20),
 password_hash VARBINARY(20),
 balance int
 );

   CREATE TABLE Reservations(
 reservationId int PRIMARY KEY,
 paid int, -- 1 means paid
 username VARCHAR(20) FOREIGN KEY REFERENCES Users(username),
 dayOfReservation int,
 fid1 int,
 fid2 int,
 price int
 );
