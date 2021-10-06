package flightapp;

import java.io.*;
import java.sql.*;
import java.util.*;
import java.security.*;
import java.security.spec.*;
import javax.crypto.*;
import javax.crypto.spec.*;

/**
 * Runs queries against a back-end database
 */
public class Query {
  // DB Connection
  private Connection conn;

  // User logged in
  private String username;

  //search list
  private HashMap<Integer, ArrayList<Flight>> searchResult = null;

  // Password hashing parameter constants
  private static final int HASH_STRENGTH = 65536;
  private static final int KEY_LENGTH = 128;

  //Transaction stuff
  private static final String BEGIN_TRANSACTION = "SET TRANSACTION ISOLATION LEVEL SERIALIZABLE; BEGIN TRANSACTION;";
  protected PreparedStatement beginTransactionStatement;

  private static final String COMMIT_TRANSACTION = "COMMIT TRANSACTION";
  protected PreparedStatement commitTransactionStatement;

  private static final String ROLLBACK_TRANSACTION = "ROLLBACK TRANSACTION";
  protected PreparedStatement rollbackTransactionStatement;
  // Canned queries
  private static final String CHECK_FLIGHT_CAPACITY = "SELECT capacity FROM Flights WHERE fid = ?";
  private PreparedStatement checkFlightCapacityStatement;

  // For check dangling
  private static final String TRANCOUNT_SQL = "SELECT @@TRANCOUNT AS tran_count";
  private PreparedStatement tranCountStatement;

  //For clearing table
  private static final String CLEAR_TABLE = "DELETE FROM Reservations; DELETE FROM Users;";
  private PreparedStatement clearTableStatement;

  //For logging in
  private static final String USER_LOGGIN = "SELECT password_salt, password_hash FROM Users WHERE username = ? ";
  private PreparedStatement userLoginStatement;

  //For creating user
  private static final String CHECK_USER_EXISTS = "SELECT U.username as username FROM Users as U WHERE lower(U.username) = ?";
  protected PreparedStatement checkUserExistsStatement;
  private static final String CREATE_USER = "INSERT INTO Users VALUES ((?),(?),(?),(?))";
  protected PreparedStatement createUserStatement;

  private static final String SEARCH_DIRECT_FLIGHT = "SELECT TOP (?) fid, "
          + "day_of_month, carrier_id,flight_num, origin_city,dest_city, actual_time,capacity, price "
          + "FROM Flights WHERE origin_city = ? AND dest_city = ? AND day_of_month =  ? AND canceled != 1"
          + "ORDER BY actual_time, fid ASC";
  private PreparedStatement searchDirectFlightStatement;

  private static final String SEARCH_INDIRECT_FLIGHT = "SELECT TOP (?) F1.day_of_month as Day_1, "
          + "F1.carrier_id as Carrier_1, F1.flight_num as Flight_1, F1.origin_city as Origin_City_1, "
          + "F1.dest_city as Dest_City_1, F1.actual_time as Duration_1, F1.capacity as Capacity_1, "
          + "F1.price as Price_1, F2.day_of_month as Day_2, F2.carrier_id as Carrier_2, "
          + "F2.flight_num as Flight_2, F2.origin_city as Origin_City_2, F2.dest_city as Dest_City_2, "
          + "F2.actual_time as Duration_2, F2.capacity as Capacity_2, F2.price as Price_2, "
          + "F1.fid as fid1, F2.fid as fid2, "
          + "F1.price + F2.price as Total_Price, F1.actual_time + F2.actual_time as Total_Duration "
          + "FROM FLIGHTS as F1, FLIGHTS as F2 "
          + "WHERE F1.origin_city = ? AND F1.dest_city = F2.origin_city AND F2.dest_city = ? "
          + "AND F1.day_of_month = ? AND F2.day_of_month = F1.day_of_month AND F1.canceled != 1 "
          + "AND F2.canceled != 1 "
          + "ORDER BY Total_Duration, F1.fid, F2.fid ASC";
  private PreparedStatement searchIndirectFlightStatement;

  private static final String CHECK_RESERVATION_DATE = "SELECT * FROM Reservations as R WHERE R.username = ? AND R.dayOfReservation = ? ";
  private PreparedStatement checkReservationDateStatement;

  private static final String CHECK_CURRENT_CAPACITY = "SELECT count(*) FROM Reservations as R WHERE R.fid1 = ? OR R.fid2 = ? ";
  private PreparedStatement checkCurrentCapacityStatement;

  private static final String ADD_RESERVATION = "INSERT into Reservations values (?, ?, ?, ?, ?, ?, ?) ";
  private PreparedStatement addReservationStatement;

  private static final String COUNT_ID = "SELECT count(*) FROM Reservations";
  private PreparedStatement countIDStatement;

  private static final String USER_RESERVATION_PRICE = "SELECT price FROM Reservations as R where R.reservationId = ? " +
          "AND R.username = ? AND R.paid = 0";
  private PreparedStatement userReservationPriceStatement;

  private static final String USER_BALANCE = "SELECT U.balance FROM Users as U WHERE U.username = ? ";
  private PreparedStatement userBalanceStatement;

  private static final String UPDATE_USER_BALANCE = "UPDATE Users SET balance = ? WHERE username = ?";
  private PreparedStatement updateUserBalanceStatement;

  private static final String UPDATE_PAY = "UPDATE Reservations SET paid = 1 WHERE username = ? AND reservationId = ?";
  private PreparedStatement updatePayStatement;

  private static final String USER_RESERVATION = "SELECT * FROM Reservations as R WHERE R.username = ?";
  private PreparedStatement userReservationStatement;

  private static final String GET_RESERVATION_FLIGHT = "SELECT day_of_month, "
          + "carrier_id, flight_num, fid, "
          + "origin_city, dest_city, "
          + "actual_time, capacity, price "
          + "FROM FLIGHTS WHERE fid = ?";
  private PreparedStatement getReservationFlightStatement;

  private static final String CANCEL_RESERVATION_SEARCH = "SELECT paid, price FROM Reservations WHERE "
          + "reservationId = ? AND username = ?";
  private PreparedStatement cancelReservationSearchStatement;

  private static final String CANCEL_RESERVATION = "DELETE FROM Reservations WHERE reservationId = ? ";
  private PreparedStatement cancelReservationStatement;

  public Query() throws SQLException, IOException {
    this(null, null, null, null);
  }

  protected Query(String serverURL, String dbName, String adminName, String password)
      throws SQLException, IOException {
    conn = serverURL == null ? openConnectionFromDbConn()
        : openConnectionFromCredential(serverURL, dbName, adminName, password);

    prepareStatements();
  }

  /**
   * Return a connecion by using dbconn.properties file
   *
   * @throws SQLException
   * @throws IOException
   */
  public static Connection openConnectionFromDbConn() throws SQLException, IOException {
    // Connect to the database with the provided connection configuration

    Properties configProps = new Properties();
    configProps.load(new FileInputStream("dbconn.properties"));
    String serverURL = configProps.getProperty("flightapp.server_url");
    String dbName = configProps.getProperty("flightapp.database_name");
    String adminName = configProps.getProperty("flightapp.username");
    String password = configProps.getProperty("flightapp.password");
    return openConnectionFromCredential(serverURL, dbName, adminName, password);
  }

  /**
   * Return a connecion by using the provided parameter.
   *
   * @param serverURL example: example.database.widows.net
   * @param dbName    database name
   * @param adminName username to login server
   * @param password  password to login server
   *
   * @throws SQLException
   */
  protected static Connection openConnectionFromCredential(String serverURL, String dbName,
      String adminName, String password) throws SQLException {
    String connectionUrl =
        String.format("jdbc:sqlserver://%s:1433;databaseName=%s;user=%s;password=%s", serverURL,
            dbName, adminName, password);
    Connection conn = DriverManager.getConnection(connectionUrl);

    // By default, automatically commit after each statement
    conn.setAutoCommit(true);

    // By default, set the transaction isolation level to serializable
    conn.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);

    return conn;
  }

  /**
   * Get underlying connection
   */
  public Connection getConnection() {
    return conn;
  }

  /**
   * Closes the application-to-database connection
   */
  public void closeConnection() throws SQLException {
    conn.close();
  }

  /**
   * Clear the data in any custom tables created.
   *
   * WARNING! Do not drop any tables and do not clear the flights table.
   */
  public void clearTables() {
    try {
      clearTableStatement.executeUpdate();
    } catch (Exception e) {
      e.printStackTrace();
    }
  }

  /*
   * prepare all the SQL statements in this method.
   */
  private void prepareStatements() throws SQLException {
    beginTransactionStatement = conn.prepareStatement(BEGIN_TRANSACTION);
    commitTransactionStatement = conn.prepareStatement(COMMIT_TRANSACTION);
    rollbackTransactionStatement = conn.prepareStatement(ROLLBACK_TRANSACTION);
    checkFlightCapacityStatement = conn.prepareStatement(CHECK_FLIGHT_CAPACITY);
    tranCountStatement = conn.prepareStatement(TRANCOUNT_SQL);
    clearTableStatement = conn.prepareStatement(CLEAR_TABLE);
    userLoginStatement = conn.prepareStatement(USER_LOGGIN);
    checkUserExistsStatement = conn.prepareStatement(CHECK_USER_EXISTS);
    createUserStatement = conn.prepareStatement(CREATE_USER);
    searchDirectFlightStatement = conn.prepareStatement(SEARCH_DIRECT_FLIGHT);
    searchIndirectFlightStatement = conn.prepareStatement(SEARCH_INDIRECT_FLIGHT);
    checkReservationDateStatement = conn.prepareStatement(CHECK_RESERVATION_DATE);
    checkCurrentCapacityStatement = conn.prepareStatement(CHECK_CURRENT_CAPACITY);
    addReservationStatement = conn.prepareStatement(ADD_RESERVATION);
    countIDStatement = conn.prepareStatement(COUNT_ID);
    userReservationPriceStatement = conn.prepareStatement(USER_RESERVATION_PRICE);
    userBalanceStatement = conn.prepareStatement(USER_BALANCE);
    updateUserBalanceStatement = conn.prepareStatement(UPDATE_USER_BALANCE);
    updatePayStatement = conn.prepareStatement(UPDATE_PAY);
    userReservationStatement = conn.prepareStatement(USER_RESERVATION);
    getReservationFlightStatement = conn.prepareStatement(GET_RESERVATION_FLIGHT);
    cancelReservationSearchStatement = conn.prepareStatement(CANCEL_RESERVATION_SEARCH);
    cancelReservationStatement = conn.prepareStatement(CANCEL_RESERVATION);
  }

  /**
   * Takes a user's username and password and attempts to log the user in.
   *
   * @param username user's username
   * @param password user's password
   *
   * @return If someone has already logged in, then return "User already logged in\n" For all other
   *         errors, return "Login failed\n". Otherwise, return "Logged in as [username]\n".
   */
  public String transaction_login(String username, String password) {
    if (this.username != null){
      return "User already logged in\n";
    }
    try {
      userLoginStatement.clearParameters();
      userLoginStatement.setString(1, username.toLowerCase());

      ResultSet result = userLoginStatement.executeQuery();

      if(result.next()){
        byte[] salt = result.getBytes("password_salt");
       // System.out.println("get salt: "+ Arrays.toString(result.getBytes("password_salt")));
        // Specify the hash parameters
        KeySpec spec = new PBEKeySpec(password.toCharArray(), salt, HASH_STRENGTH, KEY_LENGTH);

        // Generate the hash
        SecretKeyFactory factory = null;

        byte[] hash = null;
        try {
          factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1");
          hash = factory.generateSecret(spec).getEncoded();
        } catch (NoSuchAlgorithmException | InvalidKeySpecException ex) {
          throw new IllegalStateException();
        }
       // System.out.println("get hash "+Arrays.toString(hash));
        if (Arrays.toString(hash).equals(Arrays.toString(result.getBytes("password_hash")))){
          this.username = username;
          result.close();
          searchResult = null;
          return "Logged in as " + username + "\n";
        } else {
          result.close();
          return "Login failed\n";
        }
        }
      result.close();
      return "Login failed\n";
    } catch (SQLException e){
      return "Login failed\n";
    } finally {
      checkDanglingTransaction();
    }
  }

  /**
   * Implement the create user function.
   *
   * @param username   new user's username. User names are unique the system.
   * @param password   new user's password.
   * @param initAmount initial amount to deposit into the user's account, should be >= 0 (failure
   *                   otherwise).
   *
   * @return either "Created user {@code username}\n" or "Failed to create user\n" if failed.
   */
  public String transaction_createCustomer(String username, String password, int initAmount) {
    if (initAmount <0){
      return "Failed to create user\n";
    }
    int count = 3;
    try {
      // Generate a random cryptographic salt
      SecureRandom random = new SecureRandom();
      byte[] salt = new byte[16];
      random.nextBytes(salt);
      // Specify the hash parameters
      KeySpec spec = new PBEKeySpec(password.toCharArray(), salt, HASH_STRENGTH, KEY_LENGTH);

     // Generate the hash
      SecretKeyFactory factory = null;
      byte[] hash = null;
      try {
        factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1");
        hash = factory.generateSecret(spec).getEncoded();
      } catch (NoSuchAlgorithmException | InvalidKeySpecException ex) {
        throw new IllegalStateException();
      }
      beginTransaction();
      checkUserExistsStatement.clearParameters();
      checkUserExistsStatement.setString(1, username.toLowerCase());
      ResultSet result = checkUserExistsStatement.executeQuery();
      if (!result.next()){
        createUserStatement.clearParameters();
        createUserStatement.setString(1, username.toLowerCase());
       // System.out.println("create salt "+Arrays.toString(salt));
        createUserStatement.setBytes(2, salt);
        createUserStatement.setBytes(3, hash);
       // System.out.println("create hash "+Arrays.toString(hash));
        createUserStatement.setInt(4, initAmount);
        try {
          createUserStatement.executeUpdate();
          commitTransaction();
          return "Created user " + username + "\n";
        } catch (SQLException e) {
          return transaction_createCustomer(username, password, initAmount);
        }
      }
      result.close();
      rollbackTransaction();
      return "Failed to create user\n";
    } catch (SQLException e){
      return "Failed to create user\n";
    } finally {
      checkDanglingTransaction();
    }
  }

  /**
   * Implement the search function.
   *
   * Searches for flights from the given origin city to the given destination city, on the given day
   * of the month. If {@code directFlight} is true, it only searches for direct flights, otherwise
   * is searches for direct flights and flights with two "hops." Only searches for up to the number
   * of itineraries given by {@code numberOfItineraries}.
   *
   * The results are sorted based on total flight time.
   *
   * @param originCity
   * @param destinationCity
   * @param directFlight        if true, then only search for direct flights, otherwise include
   *                            indirect flights as well
   * @param dayOfMonth
   * @param numberOfItineraries number of itineraries to return
   *
   * @return If no itineraries were found, return "No flights match your selection\n". If an error
   *         occurs, then return "Failed to search\n".
   *
   *         Otherwise, the sorted itineraries printed in the following format:
   *
   *         Itinerary [itinerary number]: [number of flights] flight(s), [total flight time]
   *         minutes\n [first flight in itinerary]\n ... [last flight in itinerary]\n
   *
   *         Each flight should be printed using the same format as in the {@code Flight} class.
   *         Itinerary numbers in each search should always start from 0 and increase by 1.
   *
   * @see Flight#toString()
   */
  public String transaction_search(String originCity, String destinationCity, boolean directFlight,
      int dayOfMonth, int numberOfItineraries) {
    searchResult = new HashMap<>();
    try {
      // WARNING the below code is unsafe and only handles searches for direct flights
      // You can use the below code as a starting reference point or you can get rid
      // of it all and replace it with your own implementation.
      //

      StringBuffer sb1 = new StringBuffer();
      StringBuffer sb2 = new StringBuffer();
        try {
         // one hop itineraries
         searchDirectFlightStatement.clearParameters();
         searchDirectFlightStatement.setInt(1, numberOfItineraries);
         searchDirectFlightStatement.setString(2,  originCity);
         searchDirectFlightStatement.setString(3, destinationCity);
         searchDirectFlightStatement.setInt(4, dayOfMonth);
         ResultSet oneHopResults = searchDirectFlightStatement.executeQuery();
         ArrayList<Flight> FlightList1 = new ArrayList<Flight>();
          ArrayList<Flight> FlightList2 = new ArrayList<Flight>();
         int index = 0;
         while (oneHopResults.next()) {
           Flight Itinerary = new Flight();
           try {
             Itinerary.capacity = oneHopResults.getInt("capacity");
             Itinerary.carrierId = oneHopResults.getString("carrier_id");
             Itinerary.dayOfMonth = oneHopResults.getInt("day_of_month");
             Itinerary.destCity = oneHopResults.getString("dest_city");
             Itinerary.fid = oneHopResults.getInt("fid");
             Itinerary.flightNum = oneHopResults.getString("flight_num");
             Itinerary.originCity = oneHopResults.getString("origin_city");
             Itinerary.price = oneHopResults.getInt("price");
             Itinerary.time = oneHopResults.getInt("actual_time");
           } catch (SQLException e) { e.printStackTrace();}
           FlightList1.add(Itinerary);
           FlightList2.add(Itinerary);
           index++;
         }
         oneHopResults.close();

         if (!directFlight && index < numberOfItineraries){
           searchIndirectFlightStatement.clearParameters();
           searchIndirectFlightStatement.setInt(1, numberOfItineraries-index);
           searchIndirectFlightStatement.setString(2, originCity);
           searchIndirectFlightStatement.setString(3, destinationCity);
           searchIndirectFlightStatement.setInt(4, dayOfMonth);
           ResultSet twoHopResults = searchIndirectFlightStatement.executeQuery();
           while (twoHopResults.next()) {
             Flight Itinerary1 = new Flight();
             Flight Itinerary2 = new Flight();
             Itinerary1.dayOfMonth = twoHopResults.getInt("Day_1");
             Itinerary1.fid = twoHopResults.getInt("fid1");
             Itinerary1.carrierId = twoHopResults.getString("Carrier_1");
             Itinerary1.flightNum = twoHopResults.getString("Flight_1");
             Itinerary1.originCity = twoHopResults.getString("Origin_City_1");
             Itinerary1.destCity = twoHopResults.getString("Dest_City_1");
             Itinerary1.time = twoHopResults.getInt("Duration_1");
             Itinerary1.capacity = twoHopResults.getInt("Capacity_1");
             Itinerary1.price = twoHopResults.getInt("Price_1");
             Itinerary2.dayOfMonth = twoHopResults.getInt("Day_2");
             Itinerary2.fid = twoHopResults.getInt("fid2");
             Itinerary2.carrierId = twoHopResults.getString("Carrier_2");
             Itinerary2.flightNum = twoHopResults.getString("Flight_2");
             Itinerary2.originCity = twoHopResults.getString("Origin_City_2");
             Itinerary2.destCity = twoHopResults.getString("Dest_City_2");
             Itinerary2.time = twoHopResults.getInt("Duration_2");
             Itinerary2.capacity = twoHopResults.getInt("Capacity_2");
             Itinerary2.price = twoHopResults.getInt("Price_2");
             int totalTime = twoHopResults.getInt("Total_Duration");

             int count = 0;
             while (FlightList2.size() != 0 && totalTime >= FlightList2.get(0).time) {
               sb2.append("Itinerary " + count + ": 1 flight(s), " + FlightList2.get(0).time + " minutes\n");
               count++;
               sb2.append(FlightList2.get(0).toString() + "\n");
               ArrayList<Flight> temp = new ArrayList<>();
               temp.add(FlightList2.get(0));
               searchResult.put(count, temp);
               FlightList2.remove(0);
             }
             sb2.append("Itinerary " + count + ": 2 flight(s), " + totalTime + " minutes\n");
             count++;
             sb2.append(Itinerary1.toString() + "\n");
             sb2.append(Itinerary2.toString() + "\n");
             ArrayList<Flight> temp = new ArrayList<>();
             temp.add(Itinerary1);
             temp.add(Itinerary2);
             searchResult.put(count, temp);
             while (FlightList2.size() != 0){
               sb2.append("Itinerary " + count + ": 1 flight(s), " + FlightList2.get(0).time + " minutes\n");
               temp = new ArrayList<>();
               temp.add(FlightList2.get(0));
               searchResult.put(count, temp);
               count++;
               sb2.append(FlightList2.get(0).toString() + "\n");
               FlightList2.remove(0);
             }
           }
           twoHopResults.close();
           return sb2.toString();
         }
         int count = 0;
         while (FlightList1.size() != 0){
           sb1.append("Itinerary " + count + ": 1 flight(s), " + FlightList1.get(0).time + " minutes\n");
           ArrayList<Flight> temp = new ArrayList<>();
           temp.add(FlightList1.get(0));
           searchResult.put(count, temp);
           FlightList2.remove(0);
           count++;
           sb1.append(FlightList1.get(0).toString() + "\n");
           FlightList1.remove(0);
         }
        } catch (SQLException e) {
          e.printStackTrace();
        }
      return sb1.toString();
    } finally {
      checkDanglingTransaction();
    }
  }

  /**
   * Implements the book itinerary function.
   *
   * @param itineraryId ID of the itinerary to book. This must be one that is returned by search in
   *                    the current session.
   *
   * @return If the user is not logged in, then return "Cannot book reservations, not logged in\n".
   *         If the user is trying to book an itinerary with an invalid ID or without having done a
   *         search, then return "No such itinerary {@code itineraryId}\n". If the user already has
   *         a reservation on the same day as the one that they are trying to book now, then return
   *         "You cannot book two flights in the same day\n". For all other errors, return "Booking
   *         failed\n".
   *
   *         And if booking succeeded, return "Booked flight(s), reservation ID: [reservationId]\n"
   *         where reservationId is a unique number in the reservation system that starts from 1 and
   *         increments by 1 each time a successful reservation is made by any user in the system.
   */
  public String transaction_book(int itineraryId) {
    try {
      if (this.username == null) {
        return "Cannot book reservations, not logged in\n";
      }
      if (searchResult == null || !searchResult.containsKey(itineraryId)){
        return "No such itinerary " + itineraryId + "\n";
      }
      ArrayList<Flight> FlightList = searchResult.get(itineraryId);
      int date = FlightList.get(0).dayOfMonth;
      Flight f1 = FlightList.get(0);
      int flight2ID = 0;
      int flight2Price = 0;
      try{
        beginTransaction();
        try {
          checkReservationDateStatement.clearParameters();
          checkReservationDateStatement.setString(1, username);
          checkReservationDateStatement.setInt(2, date);

          ResultSet result = checkReservationDateStatement.executeQuery();
          if (result.next()) {
            commitTransaction();
            return "You cannot book two flights in the same day\n";
          }
        } catch (SQLException e) { //Date Check
          rollbackTransaction();
          return transaction_book(itineraryId);
        }
        try{
          checkCurrentCapacityStatement.clearParameters();
          checkCurrentCapacityStatement.setInt(1, f1.fid);
          checkCurrentCapacityStatement.setInt(2, f1.fid);
          ResultSet reservedSeat = checkCurrentCapacityStatement.executeQuery();
          reservedSeat.next();

          if (f1.capacity <=  reservedSeat.getInt(1)) {
            commitTransaction();
            return "Booking failed\n";
          }
        } catch (SQLException e) { //f1 capacity check
          rollbackTransaction();
          return transaction_book(itineraryId);
        }
        if (FlightList.size() == 2) {
          try {
            Flight f2 = FlightList.get(1);
            checkCurrentCapacityStatement.clearParameters();
            checkCurrentCapacityStatement.setInt(1, f2.fid);
            checkCurrentCapacityStatement.setInt(2, f2.fid);
            ResultSet reservedSeat = checkCurrentCapacityStatement.executeQuery();
            reservedSeat.next();
            flight2ID = f2.fid;
            flight2Price = f2.price;

            if (f2.capacity <= reservedSeat.getInt(1)) {
              commitTransaction();
              return "Booking failed\n";
            }
          } catch (SQLException e) { //f2 capacity check
            rollbackTransaction();
            return transaction_book(itineraryId);
          }
        }
        int ID = 0;
        try {
          ResultSet id = countIDStatement.executeQuery();
          id.next();
          ID = id.getInt(1);
        } catch (Exception e) {
          rollbackTransaction();
          return transaction_book(itineraryId);
        }
        addReservationStatement.clearParameters();
        addReservationStatement.setInt(1, ID+1);
        addReservationStatement.setInt(2, 0);
        addReservationStatement.setString(3, this.username);
        addReservationStatement.setInt(4, f1.dayOfMonth);
        addReservationStatement.setInt(5, f1.fid);
        addReservationStatement.setInt(6, flight2ID);
        addReservationStatement.setInt(7, f1.price + flight2Price);
        try {
          addReservationStatement.executeUpdate();
        } catch (SQLException e) {
          rollbackTransaction();
          return transaction_book(itineraryId);
        }
        commitTransaction();
        return "Booked flight(s), reservation ID: " + (ID+1) + "\n";
      } catch (SQLException e) {
        e.printStackTrace();
      }
      return "Booking failed\n";
    } finally {
      checkDanglingTransaction();
    }
  }

  /**
   * Implements the pay function.
   *
   * @param reservationId the reservation to pay for.
   *
   * @return If no user has logged in, then return "Cannot pay, not logged in\n" If the reservation
   *         is not found / not under the logged in user's name, then return "Cannot find unpaid
   *         reservation [reservationId] under user: [username]\n" If the user does not have enough
   *         money in their account, then return "User has only [balance] in account but itinerary
   *         costs [cost]\n" For all other errors, return "Failed to pay for reservation
   *         [reservationId]\n"
   *
   *         If successful, return "Paid reservation: [reservationId] remaining balance:
   *         [balance]\n" where [balance] is the remaining balance in the user's account.
   */
  public String transaction_pay(int reservationId) {
    if (this.username == null) {
      return "Cannot pay, not logged in\n";
    }
    int price = 0;
    int balance = 0;
    int afterBalance = 0;
    try {
      beginTransaction();
      try {
        userReservationPriceStatement.clearParameters();
        userReservationPriceStatement.setInt(1, reservationId);
        userReservationPriceStatement.setString(2, this.username);
        ResultSet result = userReservationPriceStatement.executeQuery();
        if (!result.next()) {
          commitTransaction();
          return "Cannot find unpaid reservation " + reservationId + " under user: "
                  + this.username + "\n";
        }
        price = result.getInt(1);
        result.close();
      } catch (SQLException e) {
        //e.printStackTrace();
        rollbackTransaction();
        return transaction_pay(reservationId);
      }
      try{
            userBalanceStatement.clearParameters();
            userBalanceStatement.setString(1, this.username);
            ResultSet resultBalance = userBalanceStatement.executeQuery();
            resultBalance.next();
            balance = resultBalance.getInt(1);
            resultBalance.close();
            afterBalance = balance - price;
            if (afterBalance < 0){
              commitTransaction();
              return "User has only " + balance +
                      " in account but itinerary costs " + price + "\n";
            }
          } catch (Exception e) { //check balance
        rollbackTransaction();
        //e.printStackTrace();
        return transaction_pay(reservationId);
      }
      try {
            updateUserBalanceStatement.clearParameters();
            updateUserBalanceStatement.setInt(1, afterBalance);
            updateUserBalanceStatement.setString(2, username);
            updateUserBalanceStatement.executeUpdate();
          } catch (Exception e) { //update balance
        rollbackTransaction();
       // e.printStackTrace();
        return transaction_pay(reservationId);
      }
      try{
            updatePayStatement.clearParameters();
            updatePayStatement.setString(1, username);
            updatePayStatement.setInt(2, reservationId);
            updatePayStatement.executeUpdate();
          } catch (Exception e) { //update pay
        //e.printStackTrace();
        rollbackTransaction();
        return transaction_pay(reservationId);
      }
      commitTransaction();
      return "Paid reservation: " + reservationId + " remaining balance: " + afterBalance + "\n";
    } catch (SQLException e) {
      return "Failed to pay for reservation " + reservationId + "\n";
    } finally {
      checkDanglingTransaction();
    }
  }

  /**
   * Implements the reservations function.
   *
   * @return If no user has logged in, then return "Cannot view reservations, not logged in\n" If
   *         the user has no reservations, then return "No reservations found\n" For all other
   *         errors, return "Failed to retrieve reservations\n"
   *
   *         Otherwise return the reservations in the following format:
   *
   *         Reservation [reservation ID] paid: [true or false]:\n [flight 1 under the
   *         reservation]\n [flight 2 under the reservation]\n Reservation [reservation ID] paid:
   *         [true or false]:\n [flight 1 under the reservation]\n [flight 2 under the
   *         reservation]\n ...
   *
   *         Each flight should be printed using the same format as in the {@code Flight} class.
   *
   * @see Flight#toString()
   */
  public String transaction_reservations() {
    if (username == null) {
      return "Cannot view reservations, not logged in\n";
    }
    try {
      StringBuffer sb = new StringBuffer();
      userReservationStatement.clearParameters();
      userReservationStatement.setString(1, this.username);
      ResultSet userReservation = userReservationStatement.executeQuery();

      Boolean temp = true;
      while (userReservation.next()){
        temp = false;
        String paid;
        if(userReservation.getInt("paid") == 1){
          paid = "true";
        } else {
          paid = "false";
        }
        sb.append("Reservation " + userReservation.getInt("reservationID") + " paid: " + paid + ":\n");
        getReservationFlightStatement.clearParameters();
        getReservationFlightStatement.setInt(1, userReservation.getInt("fid1"));
        ResultSet flight1 = getReservationFlightStatement.executeQuery();
        flight1.next();
        Flight Itinerary = new Flight();
        Itinerary.capacity = flight1.getInt("capacity");
        Itinerary.carrierId = flight1.getString("carrier_id");
        Itinerary.dayOfMonth = flight1.getInt("day_of_month");
        Itinerary.destCity = flight1.getString("dest_city");
        Itinerary.fid = flight1.getInt("fid");
        Itinerary.flightNum = flight1.getString("flight_num");
        Itinerary.originCity = flight1.getString("origin_city");
        Itinerary.price = flight1.getInt("price");
        Itinerary.time = flight1.getInt("actual_time");
        sb.append(Itinerary.toString() + "\n");
        if (userReservation.getInt("fid2") != 0) {
          getReservationFlightStatement.clearParameters();
          getReservationFlightStatement.setInt(1, userReservation.getInt("fid2"));
          ResultSet flight2 = updatePayStatement.executeQuery();
          flight2.next();
          Itinerary.capacity = flight1.getInt("capacity");
          Itinerary.carrierId = flight1.getString("carrier_id");
          Itinerary.dayOfMonth = flight1.getInt("day_of_month");
          Itinerary.destCity = flight1.getString("dest_city");
          Itinerary.fid = flight1.getInt("fid");
          Itinerary.flightNum = flight1.getString("flight_num");
          Itinerary.originCity = flight1.getString("origin_city");
          Itinerary.price = flight1.getInt("price");
          Itinerary.time = flight1.getInt("actual_time");
          sb.append(Itinerary.toString() + "\n");
        }
      }
      if(temp){
        return "No reservations found\n";
      }
      return sb.toString();
    } catch (SQLException e) {
      e.printStackTrace();
      return "Failed to retrieve reservations\n";
    } finally {
      checkDanglingTransaction();
    }
  }

  /**
   * Implements the cancel operation.
   *
   * @param reservationId the reservation ID to cancel
   *
   * @return If no user has logged in, then return "Cannot cancel reservations, not logged in\n" For
   *         all other errors, return "Failed to cancel reservation [reservationId]\n"
   *
   *         If successful, return "Canceled reservation [reservationId]\n"
   *
   *         Even though a reservation has been canceled, its ID should not be reused by the system.
   */
  public String transaction_cancel(int reservationId) {
    if (this.username == null) {
      return "Cannot cancel reservations, not logged in\n";
    }
    try {
      beginTransaction();
      try{
        cancelReservationSearchStatement.clearParameters();
        cancelReservationSearchStatement.setInt(1, reservationId);
        cancelReservationSearchStatement.setString(2, this.username);
        ResultSet reservations = cancelReservationSearchStatement.executeQuery();
        if(!reservations.next()){
          commitTransaction();
          return "Failed to cancel reservation " + reservationId + "\n";
        }
        if(reservations.getInt("paid") == 1){
          try{
            userBalanceStatement.clearParameters();
            userBalanceStatement.setString(1, this.username);
            ResultSet balance = userBalanceStatement.executeQuery();
            balance.next();
            updateUserBalanceStatement.clearParameters();
            updateUserBalanceStatement.setInt(1, balance.getInt("balance")+reservations.getInt("price"));
            updateUserBalanceStatement.setString(2, this.username);
            updateUserBalanceStatement.executeUpdate();
            balance.close();
          } catch (Exception e) {
            rollbackTransaction();
            e.printStackTrace();
           // return transaction_cancel(reservationId);
          }
        }
        try{
          cancelReservationStatement.clearParameters();
          cancelReservationStatement.setInt(1, reservationId);
          cancelReservationStatement.executeUpdate();
        } catch (SQLException e) {
          rollbackTransaction();
          e.printStackTrace();
          return transaction_cancel(reservationId);
        }
        commitTransaction();
        reservations.close();
        return "Canceled reservation " + reservationId + "\n";
      } catch (Exception e) {
        rollbackTransaction();
        e.printStackTrace();
        return transaction_cancel(reservationId);
      }
    } catch (SQLException e) {
      e.printStackTrace();
      return "Failed to cancel reservation " + reservationId + "\n";
    } finally {
      checkDanglingTransaction();
    }
  }

  /**
   * Example utility function that uses prepared statements
   */
  private int checkFlightCapacity(int fid) throws SQLException {
    checkFlightCapacityStatement.clearParameters();
    checkFlightCapacityStatement.setInt(1, fid);
    ResultSet results = checkFlightCapacityStatement.executeQuery();
    results.next();
    int capacity = results.getInt("capacity");
    results.close();

    return capacity;
  }

  /**
   * Throw IllegalStateException if transaction not completely complete, rollback.
   * 
   */
  private void checkDanglingTransaction() {
    try {
      try (ResultSet rs = tranCountStatement.executeQuery()) {
        rs.next();
        int count = rs.getInt("tran_count");
        if (count > 0) {
          throw new IllegalStateException(
              "Transaction not fully commit/rollback. Number of transaction in process: " + count);
        }
      } finally {
        conn.setAutoCommit(true);
      }
    } catch (SQLException e) {
      throw new IllegalStateException("Database error", e);
    }
  }

  private static boolean isDeadLock(SQLException ex) {
    return ex.getErrorCode() == 1205;
  }

  public void beginTransaction() throws SQLException
  {
    conn.setAutoCommit(false);
    beginTransactionStatement.executeUpdate();
  }

  public void commitTransaction() throws SQLException
  {
    commitTransactionStatement.executeUpdate();
    conn.setAutoCommit(true);
  }

  public void rollbackTransaction() throws SQLException
  {
    rollbackTransactionStatement.executeUpdate();
    conn.setAutoCommit(true);
  }

  /**
   * A class to store flight information.
   */
  class Flight {
    public int fid;
    public int dayOfMonth;
    public String carrierId;
    public String flightNum;
    public String originCity;
    public String destCity;
    public int time;
    public int capacity;
    public int price;

    @Override
    public String toString() {
      return "ID: " + fid + " Day: " + dayOfMonth + " Carrier: " + carrierId + " Number: "
          + flightNum + " Origin: " + originCity + " Dest: " + destCity + " Duration: " + time
          + " Capacity: " + capacity + " Price: " + price;
    }
  }
}
