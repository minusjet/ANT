/* Copyright 2017-2018 All Rights Reserved.
 *  Gyeonghwan Hong (redcarrottt@gmail.com)
 *
 * [Contact]
 *  Gyeonghwan Hong (redcarrottt@gmail.com)
 *
 * Licensed under the Apache License, Version 2.0(the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#include "../inc/ControlMessageReceiver.h"

#include "../inc/Core.h"
#include "../inc/NetworkSwitcher.h"

#include "../../common/inc/DebugLog.h"

#include <iostream>
#include <string.h>
#include <string>
#include <sys/types.h>
#include <thread>
#include <unistd.h>

#define THREAD_NAME "Control Message Receiving"

using namespace sc;

void ControlMessageReceiver::start_receiving_thread(void) {
  this->mReceivingThread = new std::thread(
      std::bind(&ControlMessageReceiver::receiving_thread_loop, this));
  this->mReceivingThread->detach();
}

void ControlMessageReceiver::stop_receiving_thread(void) {
  this->mReceivingThreadOn = false;
}

void ControlMessageReceiver::receiving_thread_loop(void) {
  this->mReceivingThreadOn = true;
  LOG_THREAD_LAUNCH(THREAD_NAME);

  while (this->mReceivingThreadOn) {
    this->receiving_thread_loop_internal();
  }

  LOG_THREAD_FINISH(THREAD_NAME);
  this->mReceivingThreadOn = false;
}

bool ControlMessageReceiver::receiving_thread_loop_internal(void) {
  void *message_buffer = NULL;
  Core::get_instance()->receive(&message_buffer, true);
  if (message_buffer == NULL) {
    return false;
  }

  std::string message((char *)message_buffer);

  // Find separator location (between first line and other lines)
  std::size_t separator_pos = message.find('\n');

  // Divide the message into first line & other lines
  std::string first_line(message.substr(0, separator_pos));
  std::string other_lines(message.substr(separator_pos));

  int control_message_code = std::stoi(first_line);

  switch (control_message_code) {
  case CMCode::kCMCodeConnect:
  case CMCode::kCMCodeDisconnect:
  case CMCode::kCMCodeSleep:
  case CMCode::kCMCodeWakeUp:
  case CMCode::kCMCodeDisconnectAck: {
    // Normal type
    int adapter_id = std::stoi(other_lines);
    this->on_receive_normal_message(control_message_code, adapter_id);
    break;
  }
  case CMCode::kCMCodePriv: {
    // Priv type
    this->on_receive_priv_message(other_lines);
    break;
  }
  default: {
    LOG_ERR("Unknown Control Message Code(%d)!\n%s", control_message_code,
            message.c_str());
    break;
  }
  }

  free(message_buffer);
  return true;
}

void ControlMessageReceiver::on_receive_normal_message(int control_message_code,
                                                       int adapter_id) {
  switch (control_message_code) {
  case CMCode::kCMCodeConnect: {
    LOG_VERB("Receive(Control Msg): Request(Connect %d)", adapter_id);
    NetworkSwitcher::get_instance()->connect_adapter_by_peer(adapter_id);
    break;
  }
  case CMCode::kCMCodeDisconnect: {
    LOG_VERB("Receive(Control Msg): Request(Disconnect %d)", adapter_id);
    NetworkSwitcher::get_instance()->disconnect_adapter_by_peer(adapter_id);
    break;
  }
  case CMCode::kCMCodeSleep: {
    LOG_VERB("Receive(Control Msg): Request(Sleep %d)", adapter_id);
    NetworkSwitcher::get_instance()->sleep_adapter_by_peer(adapter_id);
    break;
  }
  case CMCode::kCMCodeWakeUp: {
    LOG_VERB("Receive(Control Msg): Request(WakeUp %d)", adapter_id);
    NetworkSwitcher::get_instance()->wake_up_adapter_by_peer(adapter_id);
    break;
  }
  case CMCode::kCMCodeDisconnectAck: {
    LOG_VERB("Receive(Control Msg): Request(DisconnectAck %d)", adapter_id);
    ServerAdapter *disconnect_adapter =
        Core::get_instance()->find_adapter_by_id((int)adapter_id);
    if (disconnect_adapter == NULL) {
      LOG_WARN("Cannot find adapter %d", (int)adapter_id);
    } else {
      disconnect_adapter->peer_knows_disconnecting_on_purpose();
    }
    break;
  }
  default: {
    // Never reach here
    break;
  }
  }
}

void ControlMessageReceiver::on_receive_priv_message(std::string contents) {
  // Find separator location (between second line and other lines)
  std::size_t separator_pos = contents.find('\n');

  // Divide the message into second line & other lines
  std::string second_line(contents.substr(0, separator_pos));
  std::string priv_message(contents.substr(separator_pos));

  int priv_type = std::stoi(second_line);

  // Notify the priv message
  for (std::vector<ControlPrivMessageListener *>::iterator it =
           this->mPrivMessageListeners.begin();
       it != this->mPrivMessageListeners.end(); it++) {
    ControlPrivMessageListener *listener = *it;
    if (listener != NULL) {
      listener->on_receive_control_priv_message(priv_type, priv_message);
    }
  }
}