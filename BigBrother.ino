#include <Adafruit_GPS.h>

// what's the name of the hardware serial port?
#define GPSSerial Serial

// Connect to the GPS on the hardware port
Adafruit_GPS GPS(&GPSSerial);
     
// Set GPSECHO to 'false' to turn off echoing the GPS data to the Serial console
// Set to 'true' if you want to debug and listen to the raw GPS sentences
#define GPSECHO false

uint32_t timer = millis();

short int greenled = 6;
short int yellowled = 7;
short int redled = 8;

double initialFixLat = 0;
double initialFixLon = 0;
short int acquisitions = 0;
short int numMeasurements = 50;

float radius = 5; // radius of area in meters
float margin = 2.5; // distance from edge of radius where LED turns yellow

int numSatellites = 0;
bool goodZero = 0;


double actual(double NMEA)
{
  double degs = int(NMEA/100); //all but the last two digits before the decimal point
  double mins = int(NMEA - degs*100); //last two digits before decimal point
  double secs = (NMEA-int(NMEA)); //all digits after decimal point
  return degs + (mins/60) + (secs/60);
}

double getDistance(double lat1, double lon1, double lat2, double lon2)
{
  int R = 6371000; // Radius of the earth in m
  double dLat = deg2rad(lat2-lat1);  // deg2rad below
  double dLon = deg2rad(lon2-lon1); 
  double a = 
    sin(dLat/2) * sin(dLat/2) +
    cos(deg2rad(lat1)) * cos(deg2rad(lat2)) * 
    sin(dLon/2) * sin(dLon/2)
    ; 
  double c = 2 * atan2(sqrt(a), sqrt(1-a)); 
  double d = R * c; // Distance in m
  return d;
}

double deg2rad(double deg) {
  return deg * (PI/180);
}

int areaStatus()
{
  if (GPS.fix) {
    double distance = getDistance(initialFixLat, initialFixLon, actual(GPS.latitude), actual(GPS.longitude));
    Serial.print("distance from initial fix: "); Serial.println(distance);
    
    if (distance > radius) {
      return 2;
    }
    else if (distance > radius-margin) {
      return 1;
    }
    else {
      return 0;
    }
  }
}

void getStatus()
{
  switch(areaStatus())
  {
    case 0:
    {
      digitalWrite(greenled, HIGH);
      digitalWrite(yellowled, LOW);
      digitalWrite(redled, LOW);
      break;
    }
    case 1:
    {
      digitalWrite(greenled, LOW);
      digitalWrite(yellowled, HIGH);
      digitalWrite(redled, LOW);
      break;
    }
    case 2:
    {
      digitalWrite(greenled, LOW);
      digitalWrite(yellowled, LOW);
      digitalWrite(redled, HIGH);
      break;
    }
    default:
    {
      digitalWrite(greenled, LOW);
      digitalWrite(yellowled, LOW);
      digitalWrite(redled, LOW);
    }
  }
}


void setup()
{
  pinMode(greenled, OUTPUT);
  pinMode(yellowled, OUTPUT);
  pinMode(redled, OUTPUT);
  //while (!Serial);  // uncomment to have the sketch wait until Serial is ready
  
  // connect at 115200 so we can read the GPS fast enough and echo without dropping chars
  // also spit it out
  Serial.begin(115200);
  Serial.println("Adafruit GPS library basic test!");
     
  // 9600 NMEA is the default baud rate for Adafruit MTK GPS's- some use 4800
  GPS.begin(9600);
  // uncomment this line to turn on RMC (recommended minimum) and GGA (fix data) including altitude
  GPS.sendCommand(PMTK_SET_NMEA_OUTPUT_RMCGGA);
  // uncomment this line to turn on only the "minimum recommended" data
  //GPS.sendCommand(PMTK_SET_NMEA_OUTPUT_RMCONLY);
  // For parsing data, we don't suggest using anything but either RMC only or RMC+GGA since
  // the parser doesn't care about other sentences at this time
  // Set the update rate
  GPS.sendCommand(PMTK_SET_NMEA_UPDATE_1HZ); // 1 Hz update rate
  // For the parsing code to work nicely and have time to sort thru the data, and
  // print it out we don't suggest using anything higher than 1 Hz
     
  // Request updates on antenna status, comment out to keep quiet
  GPS.sendCommand(PGCMD_ANTENNA);

  delay(1000);
  
  // Ask for firmware version
  GPSSerial.println(PMTK_Q_RELEASE);
}

void loop() // run over and over again
{
  // read data from the GPS in the 'main loop'
  char c = GPS.read();
  // if you want to debug, this is a good time to do it!
//  if (GPSECHO)
//    if (c) Serial.print(c);
  // if a sentence is received, we can check the checksum, parse it...
  if (GPS.newNMEAreceived()) {
    // a tricky thing here is if we print the NMEA sentence, or data
    // we end up not listening and catching other sentences!
    // so be very wary if using OUTPUT_ALLDATA and trytng to print out data
//    Serial.println(GPS.lastNMEA()); // this also sets the newNMEAreceived() flag to false
    if (!GPS.parse(GPS.lastNMEA())) // this also sets the newNMEAreceived() flag to false
      return; // we can fail to parse a sentence in which case we should just wait for another
  }
  // if millis() or timer wraps around, we'll just reset it
  if (timer > millis()) timer = millis();
     
  // approximately every two seconds or so, print out the current stats
  if (millis() - timer > 1000) {
    timer = millis(); // reset the timer
    Serial.print("\nTime: ");
    Serial.print(GPS.hour, DEC); Serial.print(':');
    Serial.print(GPS.minute, DEC); Serial.print(':');
    Serial.print(GPS.seconds, DEC); Serial.print('.');
    Serial.println(GPS.milliseconds);
    Serial.print("Date: ");
    Serial.print(GPS.day, DEC); Serial.print('/');
    Serial.print(GPS.month, DEC); Serial.print("/20");
    Serial.println(GPS.year, DEC);
    Serial.print("Fix: "); Serial.print((int)GPS.fix);
    Serial.print(" quality: "); Serial.println((int)GPS.fixquality);
    if (GPS.fix) {
      Serial.print("Location: ");
      Serial.print(actual(GPS.latitude), 9); Serial.print('N');
      Serial.print(", ");
      Serial.print(actual(GPS.longitude), 9); Serial.println('W');
      Serial.print("Speed (knots): "); Serial.println(GPS.speed);
      Serial.print("Angle: "); Serial.println(GPS.angle);
      Serial.print("Altitude: "); Serial.println(GPS.altitude);
      Serial.print("Satellites: "); Serial.println((int)GPS.satellites);
      if (numSatellites*2 < (int)GPS.satellites ||
          numSatellites > (int)GPS.satellites*2)
      {
        goodZero = 0;
        numSatellites = (int)GPS.satellites;
      }
      if (goodZero == 0) {
        digitalWrite(greenled, HIGH);
        digitalWrite(yellowled, HIGH);
        digitalWrite(redled, HIGH);
        delay(500);
        digitalWrite(greenled, LOW);
        digitalWrite(yellowled, LOW);
        digitalWrite(redled, LOW);
        
        if (acquisitions++ == numMeasurements) {
          initialFixLat = actual(GPS.latitude);
          initialFixLon = actual(GPS.longitude);
          goodZero = 1;
          numMeasurements /= 4;
          acquisitions = 0;
        }
      }
      else {
        getStatus();
      }
    }
  }
}
