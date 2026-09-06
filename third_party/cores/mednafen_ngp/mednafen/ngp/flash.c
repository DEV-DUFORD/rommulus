//---------------------------------------------------------------------------
// NEOPOP : Emulator as in Dreamland
//
// Copyright (c) 2001-2002 by neopop_uk
//---------------------------------------------------------------------------

//---------------------------------------------------------------------------
//	This program is free software; you can redistribute it and/or modify
//	it under the terms of the GNU General Public License as published by
//	the Free Software Foundation; either version 2 of the License, or
//	(at your option) any later version. See also the license.txt file for
//	additional informations.
//---------------------------------------------------------------------------

#include <stdlib.h>
#include <string.h>

#include "flash.h"
#include "mem.h"
#include "rom.h"
#include "system.h"

#include "../state.h"

//-----------------------------------------------------------------------------
// Local Definitions
//-----------------------------------------------------------------------------
//This value is used to verify flash data - it is set to the
//version number that the flash description was modified for.

#define FLASH_VALID_ID		0x0053

//Number of different flash blocks, this should be enough.

#define FLASH_MAX_BLOCKS	256

typedef struct
{
   //Flash Id
   uint16_t valid_flash_id;		// = FLASH_VALID_ID

   uint16_t block_count;			//Number of flash data blocks

   uint32_t total_file_length;		// header + block[0 - block_count]

} FlashFileHeader;

typedef struct
{
	uint32_t start_address;		// 24 bit address
	uint16_t data_length;		// length of following data

	//Followed by data_length bytes of the actual data.

} FlashFileBlockHeader;

/* Local Data */
static FlashFileBlockHeader	blocks[256];
static uint16_t block_count;
static uint8_t *romm_save_image;

static bool flash_address_valid(uint32_t address, uint32_t length)
{
   uint32_t offset;
   if (!length || !ngpc_rom.data)
      return false;
   if (address >= ROM_START && address <= ROM_END &&
       length <= ROM_END + 1 - address)
      offset = address - ROM_START;
   else if (address >= HIROM_START && address <= HIROM_END &&
            length <= HIROM_END + 1 - address)
      offset = 0x200000 + address - HIROM_START;
   else
      return false;
   return offset < ngpc_rom.length && length <= ngpc_rom.length - offset;
}

/* Check the entire image before touching ROM or the active block table.
 * memcpy avoids unaligned struct reads in old .flash files. */
static bool flash_image_valid(const void *data, size_t size)
{
   FlashFileHeader header;
   const uint8_t *bytes = (const uint8_t *)data;
   size_t offset = sizeof(header);
   unsigned i;
   if (!data || size < sizeof(header))
      return false;
   memcpy(&header, bytes, sizeof(header));
   if (header.valid_flash_id != FLASH_VALID_ID ||
       header.block_count > FLASH_MAX_BLOCKS ||
       header.total_file_length != size)
      return false;
   for (i = 0; i < header.block_count; ++i)
   {
      FlashFileBlockHeader block;
      if (size - offset < sizeof(block))
         return false;
      memcpy(&block, bytes + offset, sizeof(block));
      offset += sizeof(block);
      if (block.data_length > size - offset ||
          !flash_address_valid(block.start_address, block.data_length))
         return false;
      offset += block.data_length;
   }
   return offset == size;
}

void flash_optimise_blocks(void)
{
   int i, j;

   // Bubble Sort by address
   for (i = 0; i < block_count - 1; i++)
   {
      for (j = i+1; j < block_count; j++)
      {
         //Swap?
         if (blocks[i].start_address > blocks[j].start_address)
         {
            uint16_t temp16;
            uint32_t temp32 = blocks[i].start_address;

            blocks[i].start_address = blocks[j].start_address;
            blocks[j].start_address = temp32;

            temp16 = blocks[i].data_length;
            blocks[i].data_length = blocks[j].data_length;
            blocks[j].data_length = temp16;
         }
      }
   }

   //Join contiguous blocks
   //Only advance 'i' if required, this will allow subsequent
   //blocks to be compared to the newly expanded block.
   for (i = 0; i < block_count - 1; /**/)
   {
      uint32_t end = blocks[i].start_address + blocks[i].data_length;
      uint32_t next_end = blocks[i+1].start_address + blocks[i+1].data_length;
      uint32_t merged_end = end > next_end ? end : next_end;
      //Next block lies within (or borders) this one?
      if (blocks[i+1].start_address <=
            end && merged_end - blocks[i].start_address <= UINT16_MAX)
      {
         blocks[i].data_length = (uint16_t)(merged_end - blocks[i].start_address);

         //Remove the next one.
         for (j = i+2; j < block_count; j++)
         {
            blocks[j-1].start_address = blocks[j].start_address;
            blocks[j-1].data_length = blocks[j].data_length;
         }
         block_count --;
      }
      else
      {
         i++;	// Try the next block
      }
   }
}

void do_flash_read(uint8_t *flashdata)
{
   FlashFileHeader header;
   uint8_t *fileptr;
   uint16_t i;
   uint32_t j;
   bool PREV_memory_unlock_flash_write = memory_unlock_flash_write; // kludge, hack, FIXME

   memcpy(&header, flashdata, sizeof(header));

   //Read header
   block_count = header.block_count;
   fileptr = flashdata + sizeof(FlashFileHeader);

   //Copy blocks
   memory_unlock_flash_write = 1;
   for (i = 0; i < block_count; i++)
   {
      FlashFileBlockHeader current;
      memcpy(&current, fileptr, sizeof(current));
      fileptr += sizeof(FlashFileBlockHeader);

      blocks[i].start_address = current.start_address;
      blocks[i].data_length = current.data_length;

      //Copy data
      for (j = 0; j < blocks[i].data_length; j++)
      {
         storeB(blocks[i].start_address + j, *fileptr);
         fileptr++;
      }
   }
   memory_unlock_flash_write = PREV_memory_unlock_flash_write;

   flash_optimise_blocks();		//Optimise
}

void flash_read(void)
{
   FlashFileHeader header;
   uint8_t* flashdata;

   //Initialise the internal flash configuration
   block_count              = 0;
   free(romm_save_image);
   romm_save_image = NULL;
   memset(blocks, 0, sizeof(blocks));

   header.valid_flash_id    = 0;
   header.block_count       = 0;
   header.total_file_length = 0;

   //Read flash buffer header
   if (system_io_flash_read((uint8_t*)&header, sizeof(FlashFileHeader)) == 0)
      return; //Silent failure - no flash data yet.

   //Verify correct flash id
   if (header.valid_flash_id != FLASH_VALID_ID ||
       header.block_count > FLASH_MAX_BLOCKS ||
       header.total_file_length < sizeof(header) ||
       header.total_file_length > sizeof(header) +
          FLASH_MAX_BLOCKS * (sizeof(FlashFileBlockHeader) + UINT16_MAX))
      return;

   //Read the flash data
   flashdata = (uint8_t*)malloc(header.total_file_length * sizeof(uint8_t));
   if (flashdata &&
       system_io_flash_read(flashdata, header.total_file_length) &&
       flash_image_valid(flashdata, header.total_file_length))
      do_flash_read(flashdata);

   free(flashdata);
}

void flash_write(uint32_t start_address, uint16_t length)
{
   uint16_t i;

   //Now we need a new flash command before the next flash write will work!
   memory_flash_command = false;
   if (!flash_address_valid(start_address, length))
      return;

   for (i = 0; i < block_count; i++)
   {
      //Got this block with enough bytes to cover it
      if (blocks[i].start_address <= start_address &&
            blocks[i].start_address + blocks[i].data_length >= start_address + length)
         return; //Nothing to do, block already registered.

      //Got this block with but it's length is too short
      if (blocks[i].start_address == start_address &&
            blocks[i].data_length < length)
      {
         blocks[i].data_length = length;	//Enlarge block updating.
         return;
      }
   }

   // New block needs to be added
   if (block_count == FLASH_MAX_BLOCKS)
   {
      uint32_t offset;
      /* A heavily fragmented cart can exhaust the upstream 256 slots.
       * Register the whole cartridge instead of dropping writes or overrunning
       * the table. At most 66 blocks cover the two 2 MiB ROM banks. */
      block_count = 0;
      for (offset = 0; offset < ngpc_rom.length && offset < 0x400000; )
      {
         uint32_t bank_remaining = 0x200000 - (offset & 0x1fffff);
         uint32_t count = ngpc_rom.length - offset;
         if (count > bank_remaining) count = bank_remaining;
         if (count > UINT16_MAX) count = UINT16_MAX;
         blocks[block_count].start_address = offset < 0x200000 ?
            ROM_START + offset : HIROM_START + offset - 0x200000;
         blocks[block_count++].data_length = (uint16_t)count;
         offset += count;
      }
      return;
   }
   blocks[block_count].start_address = start_address;
   blocks[block_count].data_length = length;
   block_count++;
}

uint8_t *make_flash_commit(int32_t *length)
{
   int i;
   FlashFileHeader header;
   uint8_t *flashdata, *fileptr;

   /* No flash data? */
   if (block_count == 0)
      return NULL;

   /* Optimize before writing */
   flash_optimise_blocks();

   /* Build a header */
   header.valid_flash_id    = FLASH_VALID_ID;
   header.block_count       = block_count;
   header.total_file_length = sizeof(FlashFileHeader);

   for (i = 0; i < block_count; i++)
   {
      header.total_file_length += sizeof(FlashFileBlockHeader);
      header.total_file_length += blocks[i].data_length;
   }

   /* Write the flash data */
   flashdata = (uint8_t*)malloc(header.total_file_length * sizeof(uint8_t));
   if (!flashdata)
      return NULL;

   /* Copy header */
   memcpy(flashdata, &header, sizeof(FlashFileHeader));
   fileptr = flashdata + sizeof(FlashFileHeader);

   /* Copy blocks */
   for (i = 0; i < block_count; i++)
   {
      uint32_t j;

      /* Native .flash uses an eight-byte block header. Zero padding keeps
       * the image deterministic after loading older save states. */
      memset(fileptr, 0, sizeof(FlashFileBlockHeader));
      memcpy(fileptr, &blocks[i].start_address, sizeof(uint32_t));
      memcpy(fileptr + sizeof(uint32_t), &blocks[i].data_length, sizeof(uint16_t));
      fileptr += sizeof(FlashFileBlockHeader);

      /* Copy data */
      for (j = 0; j < blocks[i].data_length; j++)
      {
         *fileptr = loadB(blocks[i].start_address + j);
         fileptr++;
      }
   }

   *length = header.total_file_length;
   return flashdata;
}

void flash_commit(void)
{
   int32_t length = 0;
   uint8_t *flashdata = make_flash_commit(&length);
   free(romm_save_image);
   romm_save_image = NULL;

   if (!flashdata)
   {
      /* An explicitly restored empty save must replace a previous sidecar,
       * or the next legacy file-based load would resurrect deleted flash. */
      if (!block_count && ngpc_rom.data)
      {
         FlashFileHeader empty = { FLASH_VALID_ID, 0, sizeof(FlashFileHeader) };
         system_io_flash_write((uint8_t *)&empty, sizeof(empty));
      }
      return;
   }

   system_io_flash_write(flashdata, length);
   free(flashdata);
}

/* The engine accepts variable-sized images. Preserve the upstream .flash
 * format, including a valid empty header before the first in-game write.
 * Size queries must never invalidate a previously returned data pointer. */
size_t romm_get_save_memory_size(void)
{
   size_t size = sizeof(FlashFileHeader);
   unsigned i;
   if (!ngpc_rom.data)
      return 0;
   flash_optimise_blocks();
   for (i = 0; i < block_count; ++i)
      size += sizeof(FlashFileBlockHeader) + blocks[i].data_length;
   return size;
}

void *romm_get_save_memory_data(void)
{
   static FlashFileHeader empty = { FLASH_VALID_ID, 0, sizeof(FlashFileHeader) };
   int32_t length = 0;
   if (!ngpc_rom.data)
      return NULL;
   free(romm_save_image);
   romm_save_image = NULL;
   if (!block_count)
      return &empty;
   romm_save_image = make_flash_commit(&length);
   return romm_save_image;
}

bool romm_restore_save_memory(const void *data, size_t size)
{
   if (!ngpc_rom.data || !ngpc_rom.orig_data || !flash_image_valid(data, size))
      return false;
   rom_reset_flash();
   do_flash_read((uint8_t *)data);
   RecacheFRM();
   return true;
}

void flash_reset(void)
{
   block_count = 0;
   if (ngpc_rom.data && ngpc_rom.orig_data)
      rom_reset_flash();
   RecacheFRM();
}
